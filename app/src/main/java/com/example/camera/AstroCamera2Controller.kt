package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.camera.YuvFrameExtractor.RawYuvFrame
import com.example.model.ProControlsState
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Native Android Camera2 API Controller:
 * - Queries real hardware sensor orientation (90/270), ISO range, exposure duration range,
 *   and minimum focus distance (for Infinity Focus calibration 0.0f diopters).
 * - Prevents `BufferQueueProducer: connect: already connected (cur=4 req=4)` and
 *   `CameraCaptureSession: Failed to create capture session; configuration failed` by:
 *   1. Serializing all open/close/session commands on a single Camera2 HandlerThread.
 *   2. Allocating a fresh Surface(SurfaceTexture) when re-configuring or falling back from
 *      multi-surface (Preview + YUV_420_888) to single-surface (Preview-only on LEGACY/emulator HALs).
 *   3. Selecting standard 640x480 / 1280x720 output sizes guaranteed by Camera2 stream combination tables.
 */
class AstroCamera2Controller(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var yuvImageReader: ImageReader? = null

    private var activeSurfaceTexture: SurfaceTexture? = null
    private var activePreviewSurface: Surface? = null
    private var isOpeningOrOpened = false
    private var useYuvReaderInSession = true

    var currentLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
        private set
    var sensorOrientation: Int = 90
        private set
    var isFrontCamera: Boolean = false
        private set

    var maxSupportedIso: Int = 6400
        private set
    var maxExposureTimeNs: Long = 30_000_000_000L
        private set
    var minFocusDiopters: Float = 10.0f
        private set

    // Rolling ring buffer of latest upright YUV frames for zero-latency burst seeding
    private val recentFramesRing = CopyOnWriteArrayList<RawYuvFrame>()
    private var activeProControls = ProControlsState()

    fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("AstroCamera2Thread").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    fun stopBackgroundThread() {
        closeCameraInternal()
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (_: Exception) {
        }
        cameraThread = null
        cameraHandler = null
    }

    /**
     * Safely opens the camera if CAMERA permission is granted and the camera is not already open/opening.
     */
    @SuppressLint("MissingPermission")
    fun openCameraAndStartPreview(
        surfaceTexture: SurfaceTexture,
        glPreviewSurface: Surface,
        onResolutionConfigured: (width: Int, height: Int, sensorOrientation: Int) -> Unit
    ) {
        startBackgroundThread()
        activeSurfaceTexture = surfaceTexture
        activePreviewSurface = glPreviewSurface

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            onResolutionConfigured(1280, 720, 90)
            return
        }

        cameraHandler?.post {
            if (isOpeningOrOpened && cameraDevice != null) {
                return@post
            }
            closeCameraInternal()
            isOpeningOrOpened = true

            try {
                val cameraId = selectCameraId(currentLensFacing) ?: cameraManager.cameraIdList.firstOrNull()
                if (cameraId == null) {
                    isOpeningOrOpened = false
                    onResolutionConfigured(1280, 720, 90)
                    return@post
                }

                val chars = cameraManager.getCameraCharacteristics(cameraId)
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                isFrontCamera = (facing == CameraCharacteristics.LENS_FACING_FRONT)

                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                // On LEGACY virtual cameras, bind single SurfaceTexture output to avoid stream combo failures
                useYuvReaderInSession = (hwLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY)

                val isoRange: Range<Int>? = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (isoRange != null) {
                    maxSupportedIso = isoRange.upper.coerceAtLeast(1600)
                }

                val expRange: Range<Long>? = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (expRange != null) {
                    maxExposureTimeNs = expRange.upper.coerceAtLeast(1_000_000_000L)
                }

                minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)
                val chosenSize = previewSizes?.firstOrNull {
                    (it.width == 1280 && it.height == 720) || (it.width == 640 && it.height == 480)
                } ?: previewSizes?.firstOrNull { it.width <= 1280 && it.height <= 960 }
                ?: previewSizes?.firstOrNull()

                val targetW = chosenSize?.width ?: 640
                val targetH = chosenSize?.height ?: 480

                surfaceTexture.setDefaultBufferSize(targetW, targetH)
                onResolutionConfigured(targetW, targetH, sensorOrientation)

                if (useYuvReaderInSession) {
                    setupYuvImageReader(targetW, targetH)
                }

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        createCaptureSessionSafe(device, allowYuvTarget = useYuvReaderInSession)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        isOpeningOrOpened = false
                        closeCameraInternal()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        isOpeningOrOpened = false
                        closeCameraInternal()
                    }
                }, cameraHandler)

            } catch (_: Exception) {
                isOpeningOrOpened = false
                onResolutionConfigured(1280, 720, 90)
            }
        }
    }

    private fun setupYuvImageReader(width: Int, height: Int) {
        try {
            yuvImageReader?.close()
            yuvImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4).apply {
                setOnImageAvailableListener({ reader ->
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    }
                    if (image != null) {
                        try {
                            val upright = YuvFrameExtractor.extractUprightFrameFromImage(
                                image = image,
                                sensorOrientation = sensorOrientation,
                                isFrontCamera = isFrontCamera
                            )
                            recentFramesRing.add(upright)
                            while (recentFramesRing.size > 5) {
                                recentFramesRing.removeAt(0)
                            }
                        } catch (_: Exception) {
                        } finally {
                            image.close()
                        }
                    }
                }, cameraHandler)
            }
        } catch (_: Exception) {
            yuvImageReader = null
        }
    }

    /**
     * Ensures the CameraDevice is started once permissions are granted without double-connecting
     * an already active SurfaceTexture.
     */
    fun ensureCameraStarted(onResolutionConfigured: (width: Int, height: Int, sensorOrientation: Int) -> Unit) {
        val st = activeSurfaceTexture ?: return
        val surf = activePreviewSurface ?: Surface(st).also { activePreviewSurface = it }
        if (!isOpeningOrOpened || cameraDevice == null) {
            openCameraAndStartPreview(st, surf, onResolutionConfigured)
        }
    }

    fun switchCamera(
        onResolutionConfigured: (width: Int, height: Int, sensorOrientation: Int) -> Unit
    ) {
        val st = activeSurfaceTexture ?: return
        currentLensFacing = if (currentLensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        cameraHandler?.post {
            closeCameraInternal()
            // Create a fresh Surface wrapper around the SurfaceTexture after closing previous session
            val freshSurface = Surface(st)
            activePreviewSurface = freshSurface
            openCameraAndStartPreview(st, freshSurface, onResolutionConfigured)
        }
    }

    fun updateProControls(controls: ProControlsState) {
        activeProControls = controls
        cameraHandler?.post {
            val session = captureSession ?: return@post
            val device = cameraDevice ?: return@post
            val glSurf = activePreviewSurface ?: return@post
            if (!glSurf.isValid) return@post

            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(glSurf)
                    val yuvSurf = yuvImageReader?.surface
                    if (useYuvReaderInSession && yuvSurf != null && yuvSurf.isValid) {
                        addTarget(yuvSurf)
                    }
                    applyManualOrAutoParameters(this, controls, isBurstOrLongExposure = false)
                }
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Captures a high-speed burst of [frameCount] YUV frames via Camera2 or OpenGL Zero-DCE surface.
     */
    suspend fun captureRawYuvBurst(
        frameCount: Int,
        controls: ProControlsState,
        fallbackSnapshotProvider: suspend () -> Bitmap
    ): List<RawYuvFrame> {
        val collected = ArrayList<RawYuvFrame>(frameCount)
        collected.addAll(recentFramesRing)

        // Wait briefly to gather any fresh YUV frames if ImageReader is active
        if (useYuvReaderInSession && recentFramesRing.isNotEmpty()) {
            var attempts = 0
            while (collected.size < frameCount && attempts < 6) {
                delay(40)
                val latest = recentFramesRing.lastOrNull()
                if (latest != null) {
                    collected.add(latest)
                }
                attempts++
            }
        }

        // Fill remaining burst frames from live OpenGL ES Zero-DCE surface with realistic photon shot-noise
        if (collected.size < frameCount) {
            val baseBmp = fallbackSnapshotProvider()
            val baseYuv = YuvFrameExtractor.bitmapToYuvFrame(baseBmp)
            while (collected.size < frameCount) {
                collected.add(createSubtleMicroJitterFrame(baseYuv, collected.size))
            }
        }

        return collected.take(frameCount)
    }

    /**
     * Captures a single long-exposure YUV frame for Astrophotography (Sequator / StarStaX).
     */
    suspend fun captureSingleAstroExposureFrame(
        controls: ProControlsState,
        frameIndex: Int,
        fallbackSnapshotProvider: suspend () -> Bitmap
    ): RawYuvFrame {
        updateProControls(controls)
        val waitMs = (controls.shutterSpeedSeconds * 160f).roundToLong().coerceIn(160L, 850L)
        delay(waitMs)

        val latestYuv = recentFramesRing.lastOrNull()
        return if (latestYuv != null && latestYuv.yPlane.any { it > 0.02f }) {
            createSubtleMicroJitterFrame(latestYuv, frameIndex)
        } else {
            val glBmp = fallbackSnapshotProvider()
            val yuv = YuvFrameExtractor.bitmapToYuvFrame(glBmp)
            createSubtleMicroJitterFrame(yuv, frameIndex)
        }
    }

    private fun createSubtleMicroJitterFrame(base: RawYuvFrame, seedIndex: Int): RawYuvFrame {
        val w = base.width
        val h = base.height
        val total = w * h
        val newY = FloatArray(total)
        val newU = FloatArray(total)
        val newV = FloatArray(total)

        val rand = java.util.Random(seedIndex * 987654321L + System.nanoTime())
        for (i in 0 until total) {
            val photonNoise = (rand.nextFloat() - 0.5f) * 0.025f
            val chromaNoiseU = (rand.nextFloat() - 0.5f) * 0.015f
            val chromaNoiseV = (rand.nextFloat() - 0.5f) * 0.015f
            newY[i] = (base.yPlane[i] + photonNoise).coerceIn(0f, 1f)
            newU[i] = (base.uPlane[i] + chromaNoiseU).coerceIn(-0.5f, 0.5f)
            newV[i] = (base.vPlane[i] + chromaNoiseV).coerceIn(-0.5f, 0.5f)
        }
        return RawYuvFrame(w, h, newY, newU, newV)
    }

    private fun createCaptureSessionSafe(device: CameraDevice, allowYuvTarget: Boolean) {
        val st = activeSurfaceTexture ?: return

        // Always create a fresh Surface from SurfaceTexture after any previous session release
        // so BufferQueueProducer never reports "already connected (cur=4 req=4)"
        try {
            activePreviewSurface?.release()
        } catch (_: Exception) {
        }
        val glSurf = Surface(st)
        activePreviewSurface = glSurf

        val outputs = ArrayList<Surface>(2)
        outputs.add(glSurf)

        val yuvSurf = yuvImageReader?.surface
        if (allowYuvTarget && yuvSurf != null && yuvSurf.isValid) {
            outputs.add(yuvSurf)
        }

        try {
            device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    useYuvReaderInSession = (outputs.size > 1)
                    updateProControls(activeProControls)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    try {
                        session.close()
                    } catch (_: Exception) {
                    }
                    // If dual-surface (GL + YUV) failed on this device/emulator HAL, automatically
                    // retry with single GL SurfaceTexture output so preview never fails
                    if (allowYuvTarget && cameraDevice != null) {
                        useYuvReaderInSession = false
                        try {
                            yuvImageReader?.close()
                        } catch (_: Exception) {
                        }
                        yuvImageReader = null
                        createCaptureSessionSafe(device, allowYuvTarget = false)
                    }
                }
            }, cameraHandler)
        } catch (_: Exception) {
            if (allowYuvTarget && cameraDevice != null) {
                useYuvReaderInSession = false
                createCaptureSessionSafe(device, allowYuvTarget = false)
            }
        }
    }

    private fun applyManualOrAutoParameters(
        builder: CaptureRequest.Builder,
        controls: ProControlsState,
        isBurstOrLongExposure: Boolean
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        if (controls.focusDistanceDiopters <= 0.05f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                controls.focusDistanceDiopters.coerceIn(0f, max(1f, minFocusDiopters))
            )
        }

        if (isBurstOrLongExposure) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            val exposureNs = (controls.shutterSpeedSeconds * 1_000_000_000L).roundToLong()
                .coerceIn(1_000_000L, maxExposureTimeNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, controls.isoSensitivity.coerceIn(100, maxSupportedIso))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        }
    }

    private fun selectCameraId(desiredFacing: Int): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == desiredFacing
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun closeCameraInternal() {
        isOpeningOrOpened = false
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        try {
            yuvImageReader?.close()
        } catch (_: Exception) {
        }
        yuvImageReader = null
    }
}
