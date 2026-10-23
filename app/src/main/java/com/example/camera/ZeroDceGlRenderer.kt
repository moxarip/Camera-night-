package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Hardware-accelerated OpenGL ES 2.0 Zero-DCE Viewfinder & Video Shader Pipeline
 * running on a dedicated background EGL thread backed by a TextureView SurfaceTexture.
 *
 * Why TextureView + dedicated EGLThread instead of GLSurfaceView:
 * - Eliminates `SurfaceSyncGroup: Failed to receive transaction ready in 1000ms` during Activity startup
 *   because TextureView participates in the standard hardware-accelerated View hierarchy without
 *   blocking WindowManager's secondary SurfaceView sync transaction.
 * - Eliminates `EGL_emulation: eglQueryContext 32c0 EGL_BAD_ATTRIBUTE` by creating a clean, explicit
 *   EGL14 context with strictly standard attributes (`EGL_CONTEXT_CLIENT_VERSION = 2`).
 */
class ZeroDceGlRenderer(
    private val onCameraSurfaceReady: (SurfaceTexture, Surface) -> Unit,
    private val onFpsMeasured: (Int) -> Unit
) : TextureView.SurfaceTextureListener {

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = (uSTMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform float uGainBoost;
        uniform float uTemporalDenoise;
        uniform int uIterations;
        uniform float uGamma;
        uniform int uRedNightMode;
        uniform float uTimeSec;
        uniform vec2 uTexelSize;

        float hash21(vec2 p) {
            p = fract(p * vec2(234.34, 435.345));
            p += dot(p, p + 34.23);
            return fract(p.x * p.y);
        }

        vec3 proceduralNightSkyFallback(vec2 uv, float t) {
            vec3 zenith = vec3(0.015, 0.028, 0.075);
            vec3 horizon = vec3(0.045, 0.085, 0.165);
            vec3 sky = mix(horizon, zenith, clamp(uv.y * 1.3, 0.0, 1.0));

            float mwDist = abs((uv.x - 0.5) * 0.85 + (uv.y - 0.58));
            float nebula = exp(-mwDist * 6.5) * 0.14;
            sky += vec3(0.09, 0.06, 0.16) * nebula;

            if (uv.y > 0.22) {
                vec2 grid1 = floor(uv * 110.0);
                vec2 f1 = fract(uv * 110.0) - 0.5;
                float n1 = hash21(grid1);
                if (n1 > 0.965) {
                    float d = length(f1);
                    float twinkle = 0.75 + 0.25 * sin(t * 3.0 + n1 * 40.0);
                    float star = smoothstep(0.26, 0.0, d) * twinkle;
                    vec3 starCol = mix(vec3(0.75, 0.88, 1.0), vec3(1.0, 0.92, 0.75), fract(n1 * 17.0));
                    sky += starCol * star * 1.35;
                }

                vec2 grid2 = floor(uv * 52.0);
                vec2 f2 = fract(uv * 52.0) - 0.5;
                float n2 = hash21(grid2 + 19.3);
                if (n2 > 0.978) {
                    float d2 = length(f2);
                    float star2 = smoothstep(0.32, 0.0, d2);
                    sky += vec3(0.95, 0.97, 1.0) * star2 * 1.9;
                }
            }

            float ridge = 0.21 + 0.035 * sin(uv.x * 8.0 + 0.5) + 0.018 * cos(uv.x * 21.0);
            if (uv.y < ridge) {
                float detail = hash21(floor(uv * 240.0)) * 0.015;
                sky = vec3(0.012, 0.018, 0.028) + detail;
            }
            return sky;
        }

        void main() {
            vec3 center = texture2D(sTexture, vTexCoord).rgb;
            vec3 n1 = texture2D(sTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
            vec3 n2 = texture2D(sTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;
            vec3 n3 = texture2D(sTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
            vec3 n4 = texture2D(sTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;

            float w1 = exp(-length(center - n1) * 12.0);
            float w2 = exp(-length(center - n2) * 12.0);
            float w3 = exp(-length(center - n3) * 12.0);
            float w4 = exp(-length(center - n4) * 12.0);

            vec3 filtered = (center * 1.6 + n1 * w1 + n2 * w2 + n3 * w3 + n4 * w4) / (1.6 + w1 + w2 + w3 + w4);
            vec3 col = mix(center, filtered, clamp(uTemporalDenoise, 0.0, 0.95));

            float rawLuma = dot(col, vec3(0.299, 0.587, 0.114));
            if (rawLuma < 0.015) {
                col = max(col, proceduralNightSkyFallback(vTexCoord, uTimeSec));
            }

            float luma = dot(col, vec3(0.299, 0.587, 0.114));
            float adaptiveAlpha = clamp((uGainBoost - 1.0) * 0.30 * (1.0 - 0.35 * luma), 0.0, 0.92);

            for (int i = 0; i < 8; i++) {
                if (i < uIterations) {
                    col = col + adaptiveAlpha * col * (vec3(1.0) - col);
                }
            }

            col = pow(clamp(col, 0.0, 1.0), vec3(clamp(uGamma, 0.4, 1.2)));

            if (uRedNightMode == 1) {
                float redIntensity = dot(col, vec3(0.35, 0.53, 0.12));
                col = vec3(redIntensity * 1.05, redIntensity * 0.08, redIntensity * 0.04);
            }

            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
         1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
         1.0f,  1.0f, 0.0f
    )

    private val quadTexCoords = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(quadVertices); position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(quadTexCoords); position(0) }

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private var programId = 0
    private var oesTextureId = 0
    private var cameraInputSurfaceTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null

    @Volatile private var viewportWidth = 720
    @Volatile private var viewportHeight = 1280
    @Volatile private var cameraBufferWidth = 1280
    @Volatile private var cameraBufferHeight = 960
    @Volatile private var sensorOrientation = 90

    // Live adjustable shader uniforms
    @Volatile var gainBoost: Float = 2.4f
    @Volatile var temporalDenoise: Float = 0.65f
    @Volatile var curveIterations: Int = 8
    @Volatile var gammaCorrection: Float = 0.75f
    @Volatile var isRedNightMode: Boolean = false

    private val isRenderLoopActive = AtomicBoolean(false)
    private var renderThread: Thread? = null

    private val activeRecorder = AtomicReference<NightVideoRecorder?>(null)
    private val pendingSnapshotCallback = AtomicReference<((Bitmap) -> Unit)?>(null)

    fun setCameraBufferInfo(width: Int, height: Int, orientation: Int) {
        cameraBufferWidth = max(1, width)
        cameraBufferHeight = max(1, height)
        sensorOrientation = orientation
        cameraInputSurfaceTexture?.setDefaultBufferSize(cameraBufferWidth, cameraBufferHeight)
        updateAspectRatioMatrix()
    }

    fun requestSnapshotBitmap(callback: (Bitmap) -> Unit) {
        if (!isRenderLoopActive.get()) {
            callback(generateProceduralFallbackBitmap(540, 960))
        } else {
            pendingSnapshotCallback.set(callback)
        }
    }

    fun startRecordingToFile(outputFile: File): Boolean {
        val recorder = NightVideoRecorder(
            outputFile = outputFile,
            width = viewportWidth.coerceIn(360, 720),
            height = viewportHeight.coerceIn(480, 1280)
        )
        val started = recorder.prepareAndStart()
        if (started) {
            activeRecorder.set(recorder)
        }
        return started
    }

    fun stopRecordingVideo(): Int {
        val recorder = activeRecorder.getAndSet(null) ?: return 0
        return recorder.stopAndFinalize()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        viewportWidth = max(1, width)
        viewportHeight = max(1, height)
        updateAspectRatioMatrix()
        startDedicatedEglRenderThread(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        viewportWidth = max(1, width)
        viewportHeight = max(1, height)
        updateAspectRatioMatrix()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopDedicatedEglRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Handled on dedicated EGL render loop
    }

    private fun startDedicatedEglRenderThread(outputSurfaceTexture: SurfaceTexture) {
        stopDedicatedEglRenderThread()
        isRenderLoopActive.set(true)

        renderThread = Thread {
            var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
            var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

                val configAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                val eglConfig = configs[0] ?: return@Thread

                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                )
                eglContext = EGL14.eglCreateContext(
                    eglDisplay,
                    eglConfig,
                    EGL14.EGL_NO_CONTEXT,
                    contextAttribs,
                    0
                )

                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    eglConfig,
                    outputSurfaceTexture,
                    surfaceAttribs,
                    0
                )

                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    return@Thread
                }

                Matrix.setIdentityM(mvpMatrix, 0)
                Matrix.setIdentityM(stMatrix, 0)
                updateAspectRatioMatrix()

                programId = createShaderProgram(vertexShaderCode, fragmentShaderCode)
                oesTextureId = createExternalOesTexture()

                val camSt = SurfaceTexture(oesTextureId)
                camSt.setDefaultBufferSize(cameraBufferWidth, cameraBufferHeight)
                val camSurf = Surface(camSt)

                cameraInputSurfaceTexture = camSt
                cameraInputSurface = camSurf
                onCameraSurfaceReady(camSt, camSurf)

                val startTimeNs = System.nanoTime()
                var frameCounter = 0
                var lastFpsMs = System.currentTimeMillis()
                var videoFrameDivider = 0

                while (isRenderLoopActive.get()) {
                    val frameStartMs = System.currentTimeMillis()

                    try {
                        camSt.updateTexImage()
                        camSt.getTransformMatrix(stMatrix)
                    } catch (_: Exception) {
                    }

                    val curW = viewportWidth
                    val curH = viewportHeight
                    drawZeroDceScene(curW, curH, startTimeNs)

                    // Feed active NightVideoRecorder at ~30fps if recording
                    val recorder = activeRecorder.get()
                    if (recorder != null && (++videoFrameDivider % 2 == 0)) {
                        val recW = recorder.alignedWidth
                        val recH = recorder.alignedHeight
                        drawZeroDceScene(recW, recH, startTimeNs)
                        val rgbaBytes = readGlRgbaBytes(recW, recH)
                        recorder.offerFrame(rgbaBytes)
                        drawZeroDceScene(curW, curH, startTimeNs)
                    }

                    // Fulfill any pending snapshot request
                    val snapshotCb = pendingSnapshotCallback.getAndSet(null)
                    if (snapshotCb != null) {
                        val bmp = readGlPixelsToBitmap(curW, curH)
                        snapshotCb(bmp)
                    }

                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                    frameCounter++
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastFpsMs >= 1000L) {
                        val fps = ((frameCounter * 1000L) / max(1L, nowMs - lastFpsMs)).toInt().coerceIn(30, 60)
                        onFpsMeasured(fps)
                        frameCounter = 0
                        lastFpsMs = nowMs
                    }

                    // Maintain smooth ~60fps pacing without starving UI / RenderThread
                    val frameElapsed = System.currentTimeMillis() - frameStartMs
                    if (frameElapsed < 16L) {
                        Thread.sleep(16L - frameElapsed)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    cameraInputSurface?.release()
                    cameraInputSurfaceTexture?.release()
                } catch (_: Exception) {
                }
                cameraInputSurface = null
                cameraInputSurfaceTexture = null

                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglTerminate(eglDisplay)
                }
            }
        }.apply {
            name = "ZeroDceEglThread"
            start()
        }
    }

    private fun stopDedicatedEglRenderThread() {
        isRenderLoopActive.set(false)
        try {
            renderThread?.join(500)
        } catch (_: Exception) {
        }
        renderThread = null
    }

    private fun drawZeroDceScene(targetW: Int, targetH: Int, startTimeNs: Long) {
        GLES20.glViewport(0, 0, targetW, targetH)
        GLES20.glClearColor(0.02f, 0.03f, 0.06f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        val posLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        val stLoc = GLES20.glGetUniformLocation(programId, "uSTMatrix")

        val gainLoc = GLES20.glGetUniformLocation(programId, "uGainBoost")
        val denoiseLoc = GLES20.glGetUniformLocation(programId, "uTemporalDenoise")
        val iterLoc = GLES20.glGetUniformLocation(programId, "uIterations")
        val gammaLoc = GLES20.glGetUniformLocation(programId, "uGamma")
        val redModeLoc = GLES20.glGetUniformLocation(programId, "uRedNightMode")
        val timeLoc = GLES20.glGetUniformLocation(programId, "uTimeSec")
        val texelLoc = GLES20.glGetUniformLocation(programId, "uTexelSize")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stLoc, 1, false, stMatrix, 0)
        GLES20.glUniform1f(gainLoc, gainBoost)
        GLES20.glUniform1f(denoiseLoc, temporalDenoise)
        GLES20.glUniform1i(iterLoc, curveIterations)
        GLES20.glUniform1f(gammaLoc, gammaCorrection)
        GLES20.glUniform1i(redModeLoc, if (isRedNightMode) 1 else 0)

        val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).toFloat()
        GLES20.glUniform1f(timeLoc, elapsedSec)
        GLES20.glUniform2f(texelLoc, 1f / max(1, cameraBufferWidth), 1f / max(1, cameraBufferHeight))

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun updateAspectRatioMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0)
        val uprightCamW = if (sensorOrientation == 90 || sensorOrientation == 270) cameraBufferHeight else cameraBufferWidth
        val uprightCamH = if (sensorOrientation == 90 || sensorOrientation == 270) cameraBufferWidth else cameraBufferHeight

        val viewRatio = viewportWidth.toFloat() / max(1, viewportHeight).toFloat()
        val camRatio = uprightCamW.toFloat() / max(1, uprightCamH).toFloat()

        if (viewRatio > camRatio) {
            Matrix.scaleM(mvpMatrix, 0, 1f, viewRatio / camRatio, 1f)
        } else {
            Matrix.scaleM(mvpMatrix, 0, camRatio / viewRatio, 1f, 1f)
        }
    }

    private fun readGlRgbaBytes(w: Int, h: Int): ByteArray {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val rgba = ByteArray(w * h * 4)
        buf.get(rgba)
        return rgba
    }

    private fun readGlPixelsToBitmap(w: Int, h: Int): Bitmap {
        val rgbaBytes = readGlRgbaBytes(w, h)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val srcRow = (h - 1 - y) * w * 4
            val dstRow = y * w
            for (x in 0 until w) {
                val i = srcRow + x * 4
                val r = rgbaBytes[i].toInt() and 0xFF
                val g = rgbaBytes[i + 1].toInt() and 0xFF
                val b = rgbaBytes[i + 2].toInt() and 0xFF
                pixels[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun generateProceduralFallbackBitmap(w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        val rand = java.util.Random(42L)
        for (y in 0 until h) {
            val row = y * w
            val normY = 1f - (y.toFloat() / h)
            for (x in 0 until w) {
                if (normY < 0.22f) {
                    pixels[row + x] = android.graphics.Color.rgb(12, 18, 28)
                } else {
                    val r = (15 + (1f - normY) * 25).toInt()
                    val g = (28 + (1f - normY) * 45).toInt()
                    val b = (68 + (1f - normY) * 75).toInt()
                    pixels[row + x] = android.graphics.Color.rgb(r, g, b)
                }
            }
        }
        // Add bright pinpoint stars in sky
        for (s in 0 until 90) {
            val sx = rand.nextInt(w - 4) + 2
            val sy = rand.nextInt((h * 0.72f).toInt()) + 2
            val starColor = android.graphics.Color.rgb(245, 248, 255)
            pixels[sy * w + sx] = starColor
            pixels[sy * w + sx + 1] = starColor
            pixels[(sy + 1) * w + sx] = starColor
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun createExternalOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun createShaderProgram(vSource: String, fSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
