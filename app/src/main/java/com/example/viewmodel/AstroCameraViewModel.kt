package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.AstroCamera2Controller
import com.example.camera.HdrPlusBurstStacker
import com.example.camera.SequatorAstroEngine
import com.example.camera.ZeroDceGlRenderer
import com.example.model.AstroSubMode
import com.example.model.CameraMode
import com.example.model.ExportedMediaItem
import com.example.model.ProControlsState
import com.example.model.StackingProgressState
import com.example.model.TripodTelemetry
import com.example.sensors.TripodSensorMonitor
import com.example.storage.MediaStoreExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class AstroCameraViewModel(application: Application) : AndroidViewModel(application) {

    val cameraController = AstroCamera2Controller(application)
    private val tripodMonitor = TripodSensorMonitor(application)
    private val hdrPlusStacker = HdrPlusBurstStacker()
    private val sequatorEngine = SequatorAstroEngine()
    val mediaExporter = MediaStoreExporter(application)

    private val _selectedMode = MutableStateFlow(CameraMode.LIVE_NIGHT_VISION)
    val selectedMode: StateFlow<CameraMode> = _selectedMode.asStateFlow()

    private val _proControls = MutableStateFlow(ProControlsState())
    val proControls: StateFlow<ProControlsState> = _proControls.asStateFlow()

    private val _isRedNightMode = MutableStateFlow(false)
    val isRedNightMode: StateFlow<Boolean> = _isRedNightMode.asStateFlow()

    private val _liveFps = MutableStateFlow(60)
    val liveFps: StateFlow<Int> = _liveFps.asStateFlow()

    val tripodTelemetry: StateFlow<TripodTelemetry> = tripodMonitor.telemetry

    private val _isRecordingVideo = MutableStateFlow(false)
    val isRecordingVideo: StateFlow<Boolean> = _isRecordingVideo.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private val _stackingProgress = MutableStateFlow(StackingProgressState())
    val stackingProgress: StateFlow<StackingProgressState> = _stackingProgress.asStateFlow()

    private val _exportedItems = MutableStateFlow<List<ExportedMediaItem>>(emptyList())
    val exportedItems: StateFlow<List<ExportedMediaItem>> = _exportedItems.asStateFlow()

    private val _selectedPreviewItem = MutableStateFlow<ExportedMediaItem?>(null)
    val selectedPreviewItem: StateFlow<ExportedMediaItem?> = _selectedPreviewItem.asStateFlow()

    private val _statusBannerAr = MutableStateFlow("محرك Zero-DCE GPU نشط • معالجة لحظية 60fps")
    val statusBannerAr: StateFlow<String> = _statusBannerAr.asStateFlow()

    private var activeGlSurface: Surface? = null
    private var recordingTimerJob: Job? = null
    private var activeStackingJob: Job? = null
    private var tempRecordingFile: File? = null

    val glRenderer = ZeroDceGlRenderer(
        onCameraSurfaceReady = { surfaceTexture: SurfaceTexture, surface: Surface ->
            activeGlSurface = surface
            cameraController.openCameraAndStartPreview(surfaceTexture, surface) { w, h, orientation ->
                syncRendererUniforms(w, h, orientation)
            }
        },
        onFpsMeasured = { fps ->
            _liveFps.value = fps.coerceIn(30, 60)
        }
    )

    init {
        tripodMonitor.startMonitoring()
        syncShaderWithControls(_proControls.value)
    }

    private fun syncRendererUniforms(w: Int, h: Int, orientation: Int) {
        glRenderer.setCameraBufferInfo(w, h, orientation)
        syncShaderWithControls(_proControls.value)
    }

    private fun syncShaderWithControls(controls: ProControlsState) {
        glRenderer.gainBoost = controls.zeroDceGainBoost
        glRenderer.temporalDenoise = controls.temporalDenoising
        glRenderer.curveIterations = controls.zeroDceIterations
        glRenderer.gammaCorrection = controls.gammaCorrection
        glRenderer.isRedNightMode = _isRedNightMode.value
    }

    fun selectMode(mode: CameraMode) {
        if (_stackingProgress.value.isStacking || _isRecordingVideo.value) return
        _selectedMode.value = mode
        _statusBannerAr.value = when (mode) {
            CameraMode.LIVE_NIGHT_VISION -> "وضع الرؤية الليلية الحية والفيديو (Zero-DCE 60fps)"
            CameraMode.NIGHT_SIGHT_HDR -> "وضع التصوير الليلي المحوسب (HDR+ Burst Alignment)"
            CameraMode.ASTRO_SEQUATOR -> {
                if (tripodTelemetry.value.isTripodStable) {
                    "الهاتف ثابت على حامل • وضع تصوير النجوم والفضاء (Sequator) جاهز"
                } else {
                    "وضع الفضاء نشط • يُنصح بتثبيت الهاتف لأفضل محاذاة للنجوم"
                }
            }
        }
    }

    fun toggleRedNightMode() {
        val next = !_isRedNightMode.value
        _isRedNightMode.value = next
        glRenderer.isRedNightMode = next
    }

    fun onPermissionsGranted() {
        cameraController.ensureCameraStarted { w, h, orientation ->
            glRenderer.setCameraBufferInfo(w, h, orientation)
        }
    }

    fun switchCameraLens() {
        cameraController.switchCamera { w, h, orientation ->
            glRenderer.setCameraBufferInfo(w, h, orientation)
        }
    }

    fun updateZeroDceGain(gain: Float) {
        _proControls.update { it.copy(zeroDceGainBoost = gain.coerceIn(1.0f, 5.0f)) }
        syncShaderWithControls(_proControls.value)
    }

    fun updateTemporalDenoise(denoise: Float) {
        _proControls.update { it.copy(temporalDenoising = denoise.coerceIn(0.0f, 0.92f)) }
        syncShaderWithControls(_proControls.value)
    }

    fun updateZeroDceIterations(iterations: Int) {
        _proControls.update { it.copy(zeroDceIterations = iterations.coerceIn(4, 8)) }
        syncShaderWithControls(_proControls.value)
    }

    fun updateHdrBurstCount(frames: Int) {
        _proControls.update { it.copy(hdrBurstFrameCount = frames.coerceIn(8, 15)) }
    }

    fun updateChromaDenoise(strength: Float) {
        _proControls.update { it.copy(chromaDenoiseStrength = strength.coerceIn(0f, 1f)) }
    }

    fun updateLumaSharpness(sharpness: Float) {
        _proControls.update { it.copy(lumaSharpness = sharpness.coerceIn(0f, 1f)) }
    }

    fun updateAstroSubMode(subMode: AstroSubMode) {
        _proControls.update { it.copy(astroSubMode = subMode) }
    }

    fun toggleForegroundFreeze() {
        _proControls.update { it.copy(foregroundFreezeEnabled = !it.foregroundFreezeEnabled) }
    }

    fun updateShutterSpeed(seconds: Float) {
        _proControls.update { it.copy(shutterSpeedSeconds = seconds.coerceIn(0.5f, 30.0f)) }
        cameraController.updateProControls(_proControls.value)
    }

    fun updateIsoSensitivity(iso: Int) {
        _proControls.update { it.copy(isoSensitivity = iso.coerceIn(100, 6400)) }
        cameraController.updateProControls(_proControls.value)
    }

    fun updateFocusDistance(diopters: Float) {
        _proControls.update { it.copy(focusDistanceDiopters = diopters.coerceIn(0.0f, 10.0f)) }
        cameraController.updateProControls(_proControls.value)
    }

    fun calibrateInfinityFocus() {
        updateFocusDistance(0.0f)
        _statusBannerAr.value = "تم قفل التركيز البؤري اللانهائي للنجوم (0.00 Diopters ∞)"
    }

    fun updateTargetAstroFrames(frames: Int) {
        _proControls.update { it.copy(targetAstroFrames = frames.coerceIn(6, 40)) }
    }

    fun openMediaPreviewModal(item: ExportedMediaItem?) {
        _selectedPreviewItem.value = item
    }

    /**
     * Primary Shutter Action dispatcher based on current mode.
     */
    fun onPrimaryShutterClicked() {
        when (_selectedMode.value) {
            CameraMode.LIVE_NIGHT_VISION -> captureInstantZeroDcePhoto()
            CameraMode.NIGHT_SIGHT_HDR -> startHdrPlusBurstCapture()
            CameraMode.ASTRO_SEQUATOR -> {
                if (_stackingProgress.value.isStacking) {
                    finishAstroStackEarlyAndSave()
                } else {
                    startAstrophotographyStacking()
                }
            }
        }
    }

    /**
     * Mode 1: Instant Zero-DCE Enhanced Photo Capture -> DCIM/AstroNightCam
     */
    private fun captureInstantZeroDcePhoto() {
        viewModelScope.launch {
            _statusBannerAr.value = "جاري التقاط ومعالجة صورة الرؤية الليلية Zero-DCE..."
            val bmp = captureGlSnapshotBitmap()
            val controls = _proControls.value
            val details = "Zero-DCE Gain ${String.format("%.1f", controls.zeroDceGainBoost)}x • Denoise ${(controls.temporalDenoising * 100).roundToInt()}%"
            val exported = mediaExporter.exportBitmapToGallery(
                bitmap = bmp,
                modePrefix = "ZeroDCE_Night",
                captureDetailsAr = details
            )
            if (exported != null) {
                _exportedItems.update { listOf(exported) + it }
                _statusBannerAr.value = "تم الحفظ في المعرض: DCIM/AstroNightCam/${exported.displayName}"
            }
        }
    }

    /**
     * Mode 1: Toggle Live Zero-DCE Night Video Recording with strict PTS -> Movies/AstroNightCam
     */
    fun toggleNightVideoRecording() {
        if (_isRecordingVideo.value) {
            stopNightVideoRecording()
        } else {
            startNightVideoRecording()
        }
    }

    private fun startNightVideoRecording() {
        val context = getApplication<Application>()
        val tempFile = File(context.cacheDir, "recording_night_${System.currentTimeMillis()}.mp4")
        tempRecordingFile = tempFile

        val started = glRenderer.startRecordingToFile(tempFile)
        if (started) {
            _isRecordingVideo.value = true
            _recordingDurationSec.value = 0
            _statusBannerAr.value = "جاري تسجيل فيديو ليلي مضاء (Strict PTS + AAC Audio)..."
            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch {
                val startMs = System.currentTimeMillis()
                while (_isRecordingVideo.value) {
                    delay(250)
                    _recordingDurationSec.value = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                }
            }
        } else {
            _statusBannerAr.value = "تعذر بدء مشفر الفيديو؛ تم التقاط إطار ليلي فوري"
            captureInstantZeroDcePhoto()
        }
    }

    private fun stopNightVideoRecording() {
        recordingTimerJob?.cancel()
        _isRecordingVideo.value = false
        viewModelScope.launch {
            _statusBannerAr.value = "جاري دمج وحفظ الفيديو الليلي في Movies/AstroNightCam..."
            val thumbBmp = captureGlSnapshotBitmap()
            val actualDuration = glRenderer.stopRecordingVideo().coerceAtLeast(_recordingDurationSec.value.coerceAtLeast(1))
            val tempFile = tempRecordingFile
            if (tempFile != null && tempFile.exists()) {
                val controls = _proControls.value
                val details = "فيديو Zero-DCE (${actualDuration}ث) • Gain ${String.format("%.1f", controls.zeroDceGainBoost)}x"
                val exported = mediaExporter.exportVideoFileToGallery(
                    tempVideoFile = tempFile,
                    width = thumbBmp.width,
                    height = thumbBmp.height,
                    durationSeconds = actualDuration,
                    captureDetailsAr = details,
                    previewThumbnail = thumbBmp
                )
                if (exported != null) {
                    _exportedItems.update { listOf(exported) + it }
                    _statusBannerAr.value = "تم حفظ الفيديو في المعرض: Movies/AstroNightCam/${exported.displayName}"
                }
            }
        }
    }

    /**
     * Mode 2: Night Sight HDR+ Burst Stacking (8 to 15 RAW/YUV frames + Optical Flow Block Matching)
     */
    private fun startHdrPlusBurstCapture() {
        if (_stackingProgress.value.isStacking) return
        val controls = _proControls.value
        val totalFrames = controls.hdrBurstFrameCount

        activeStackingJob = viewModelScope.launch {
            _stackingProgress.value = StackingProgressState(
                isStacking = true,
                mode = CameraMode.NIGHT_SIGHT_HDR,
                currentFrame = 0,
                totalFrames = totalFrames,
                estimatedRemainingSeconds = totalFrames / 2,
                statusMessageAr = "جاري التقاط دفعة سريعة ($totalFrames إطارات YUV)..."
            )

            val rawFrames = cameraController.captureRawYuvBurst(
                frameCount = totalFrames,
                controls = controls,
                fallbackSnapshotProvider = { captureGlSnapshotBitmap() }
            )

            val finalBitmap = hdrPlusStacker.processBurstStack(
                frames = rawFrames,
                blockSize = controls.alignmentBlockSize,
                chromaDenoiseStrength = controls.chromaDenoiseStrength,
                lumaSharpness = controls.lumaSharpness,
                zeroDceGain = controls.zeroDceGainBoost
            ) { step ->
                val remSec = ((totalFrames - step.mergedFramesCount) * 0.35f).roundToInt().coerceAtLeast(0)
                _stackingProgress.value = StackingProgressState(
                    isStacking = true,
                    mode = CameraMode.NIGHT_SIGHT_HDR,
                    currentFrame = step.mergedFramesCount,
                    totalFrames = step.totalFrames,
                    alignedStarsCount = 0,
                    currentDisplacementPx = step.meanMotionShiftPx,
                    estimatedRemainingSeconds = remSec,
                    statusMessageAr = "محاذاة Optical Flow ودمج HDR+ (${step.mergedFramesCount}/${step.totalFrames}) • +${String.format("%.1f", step.snrGainDb)}dB SNR",
                    progressivePreviewBitmap = step.progressiveBitmap,
                    snrImprovementDb = step.snrGainDb
                )
                delay(65)
            }

            val details = "HDR+ Stack (${totalFrames} Frames) • Chroma Denoise ${(controls.chromaDenoiseStrength * 100).roundToInt()}%"
            val exported = mediaExporter.exportBitmapToGallery(
                bitmap = finalBitmap,
                modePrefix = "HDRPlus_NightSight",
                captureDetailsAr = details
            )
            if (exported != null) {
                _exportedItems.update { listOf(exported) + it }
                _statusBannerAr.value = "تم حفظ صورة HDR+ في: DCIM/AstroNightCam/${exported.displayName}"
            }
            _stackingProgress.value = StackingProgressState(isStacking = false)
        }
    }

    /**
     * Mode 3: Astrophotography & Star Trails (Sequator Deep-Sky Alignment + Foreground Freeze / StarStaX)
     */
    private fun startAstrophotographyStacking() {
        val controls = _proControls.value
        val targetFrames = controls.targetAstroFrames
        sequatorEngine.resetSession()

        activeStackingJob = viewModelScope.launch {
            var latestCompositeBitmap: Bitmap? = null
            val stepSec = controls.shutterSpeedSeconds.coerceIn(0.5f, 30f)

            _stackingProgress.value = StackingProgressState(
                isStacking = true,
                mode = CameraMode.ASTRO_SEQUATOR,
                currentFrame = 0,
                totalFrames = targetFrames,
                estimatedRemainingSeconds = (targetFrames * stepSec).roundToInt(),
                statusMessageAr = "بدء تعريض الفضاء وتحديد نقاط النجوم (Sequator Engine)..."
            )

            try {
                for (frameIdx in 1..targetFrames) {
                    if (!_stackingProgress.value.isStacking) break

                    val yuvFrame = cameraController.captureSingleAstroExposureFrame(
                        controls = _proControls.value,
                        frameIndex = frameIdx,
                        fallbackSnapshotProvider = { captureGlSnapshotBitmap() }
                    )

                    val stepResult = sequatorEngine.processNextAstroFrame(
                        frame = yuvFrame,
                        subMode = _proControls.value.astroSubMode,
                        foregroundFreezeEnabled = _proControls.value.foregroundFreezeEnabled,
                        autoLightPollutionReduce = _proControls.value.autoLightPollutionReduce,
                        zeroDceGain = _proControls.value.zeroDceGainBoost,
                        targetFrames = targetFrames
                    )

                    latestCompositeBitmap = stepResult.progressiveBitmap
                    val remainingFrames = (targetFrames - frameIdx).coerceAtLeast(0)
                    val remSeconds = (remainingFrames * _proControls.value.shutterSpeedSeconds).roundToInt()
                    val snrDb = (10f * kotlin.math.log10(frameIdx.toFloat())).coerceAtLeast(0f)

                    val modeLabel = if (_proControls.value.astroSubMode == AstroSubMode.DEEP_SKY_STACKING) {
                        "تكديس النجوم (${stepResult.detectedStarsCount} نجمة متطابقة)"
                    } else {
                        "مسارات النجوم Lighten Blending"
                    }

                    _stackingProgress.value = StackingProgressState(
                        isStacking = true,
                        mode = CameraMode.ASTRO_SEQUATOR,
                        currentFrame = frameIdx,
                        totalFrames = targetFrames,
                        alignedStarsCount = stepResult.detectedStarsCount,
                        currentDisplacementPx = stepResult.driftCompensationPx,
                        estimatedRemainingSeconds = remSeconds,
                        statusMessageAr = "$modeLabel • إطار $frameIdx/$targetFrames",
                        progressivePreviewBitmap = latestCompositeBitmap,
                        snrImprovementDb = snrDb
                    )
                }
            } finally {
                val finalBmp = latestCompositeBitmap ?: captureGlSnapshotBitmap()
                val subPrefix = if (_proControls.value.astroSubMode == AstroSubMode.DEEP_SKY_STACKING) {
                    "Astro_Sequator"
                } else {
                    "StarTrails_StarStaX"
                }
                val details = "${_proControls.value.astroSubMode.titleAr} • ISO ${_proControls.value.isoSensitivity} • ${_proControls.value.shutterSpeedSeconds}s"
                val exported = mediaExporter.exportBitmapToGallery(
                    bitmap = finalBmp,
                    modePrefix = subPrefix,
                    captureDetailsAr = details
                )
                if (exported != null) {
                    _exportedItems.update { listOf(exported) + it }
                    _statusBannerAr.value = "تم حفظ الصورة الفلكية في: DCIM/AstroNightCam/${exported.displayName}"
                }
                _stackingProgress.value = StackingProgressState(isStacking = false)
            }
        }
    }

    fun finishAstroStackEarlyAndSave() {
        _stackingProgress.update { it.copy(isStacking = false, statusMessageAr = "جاري إنهاء التكديس وحفظ الصورة الفلكية...") }
    }

    private suspend fun captureGlSnapshotBitmap(): Bitmap = suspendCancellableCoroutine { cont ->
        glRenderer.requestSnapshotBitmap { bmp ->
            if (cont.isActive) {
                cont.resume(bmp)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tripodMonitor.stopMonitoring()
        cameraController.stopBackgroundThread()
    }
}
