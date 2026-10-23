package com.example.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Handles strict YUV_420_888 row-stride and pixel-stride extraction from Camera2 ImageReader,
 * along with exact sensor orientation (90/270/180/0) rotation so that portrait and landscape
 * captures never suffer from aspect ratio compression or row-padding shear distortion.
 */
object YuvFrameExtractor {

    class RawYuvFrame(
        val width: Int,
        val height: Int,
        val yPlane: FloatArray,
        val uPlane: FloatArray,
        val vPlane: FloatArray
    )

    /**
     * Extracts YUV_420_888 planes with explicit rowStride and pixelStride handling,
     * and rotates coordinates directly into upright orientation matching [sensorOrientation].
     */
    fun extractUprightFrameFromImage(image: Image, sensorOrientation: Int, isFrontCamera: Boolean = false): RawYuvFrame {
        val srcW = image.width
        val srcH = image.height

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride

        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        val yBytes = ByteArray(yBuffer.remaining())
        yBuffer.get(yBytes)
        val uBytes = ByteArray(uBuffer.remaining())
        uBuffer.get(uBytes)
        val vBytes = ByteArray(vBuffer.remaining())
        vBuffer.get(vBytes)

        // Downscale if resolution is ultra-large (>1280px) to keep multi-frame burst alignment fast & responsive
        val step = if (max(srcW, srcH) > 1280) 2 else 1
        val sampledW = srcW / step
        val sampledH = srcH / step

        val swapDimensions = (sensorOrientation == 90 || sensorOrientation == 270)
        val outW = if (swapDimensions) sampledH else sampledW
        val outH = if (swapDimensions) sampledW else sampledH

        val totalPixels = outW * outH
        val outY = FloatArray(totalPixels)
        val outU = FloatArray(totalPixels)
        val outV = FloatArray(totalPixels)

        for (sy in 0 until sampledH) {
            val origY = sy * step
            val yRowOffset = origY * yRowStride
            val uvRowIndex = origY shr 1
            val uRowOffset = uvRowIndex * uRowStride
            val vRowOffset = uvRowIndex * vRowStride

            for (sx in 0 until sampledW) {
                val origX = sx * step
                val yIdx = yRowOffset + origX * yPixelStride
                val uvColIndex = origX shr 1
                val uIdx = uRowOffset + uvColIndex * uPixelStride
                val vIdx = vRowOffset + uvColIndex * vPixelStride

                val yVal = if (yIdx in yBytes.indices) (yBytes[yIdx].toInt() and 0xFF) / 255f else 0f
                val uVal = if (uIdx in uBytes.indices) ((uBytes[uIdx].toInt() and 0xFF) - 128) / 255f else 0f
                val vVal = if (vIdx in vBytes.indices) ((vBytes[vIdx].toInt() and 0xFF) - 128) / 255f else 0f

                // Map (sx, sy) to upright (dx, dy) according to sensorOrientation
                var dx: Int
                val dy: Int
                when (sensorOrientation) {
                    90 -> {
                        dx = sampledH - 1 - sy
                        dy = sx
                    }
                    270 -> {
                        dx = sy
                        dy = sampledW - 1 - sx
                    }
                    180 -> {
                        dx = sampledW - 1 - sx
                        dy = sampledH - 1 - sy
                    }
                    else -> {
                        dx = sx
                        dy = sy
                    }
                }

                if (isFrontCamera) {
                    dx = outW - 1 - dx
                }

                val outIndex = dy * outW + dx
                if (outIndex in 0 until totalPixels) {
                    outY[outIndex] = yVal
                    outU[outIndex] = uVal
                    outV[outIndex] = vVal
                }
            }
        }

        return RawYuvFrame(
            width = outW,
            height = outH,
            yPlane = outY,
            uPlane = outU,
            vPlane = outV
        )
    }

    /**
     * Converts a RawYuvFrame into an ARGB_8888 Bitmap with optional Zero-DCE curve enhancement.
     */
    fun yuvFrameToBitmap(
        frame: RawYuvFrame,
        zeroDceGain: Float = 1.0f,
        curveIterations: Int = 0
    ): Bitmap {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        val alpha = ((zeroDceGain - 1.0f) * 0.28f).coerceIn(0f, 0.92f)

        for (i in pixels.indices) {
            var y = frame.yPlane[i].coerceIn(0f, 1f)
            // Zero-DCE iterative light-enhancement curve: LE(x) = x + alpha * x * (1 - x)
            for (iter in 0 until curveIterations) {
                y = (y + alpha * y * (1f - y)).coerceIn(0f, 1f)
            }

            val u = frame.uPlane[i]
            val v = frame.vPlane[i]

            val r = (y + 1.402f * v).coerceIn(0f, 1f)
            val g = (y - 0.344136f * u - 0.714136f * v).coerceIn(0f, 1f)
            val b = (y + 1.772f * u).coerceIn(0f, 1f)

            pixels[i] = Color.rgb(
                (r * 255f).roundToInt().coerceIn(0, 255),
                (g * 255f).roundToInt().coerceIn(0, 255),
                (b * 255f).roundToInt().coerceIn(0, 255)
            )
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Converts an existing Bitmap (e.g. fallback or test frame) into a RawYuvFrame.
     */
    fun bitmapToYuvFrame(bitmap: Bitmap): RawYuvFrame {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val yPlane = FloatArray(w * h)
        val uPlane = FloatArray(w * h)
        val vPlane = FloatArray(w * h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f

            val y = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
            val u = (-0.168736f * r - 0.331264f * g + 0.5f * b).coerceIn(-0.5f, 0.5f)
            val v = (0.5f * r - 0.418688f * g - 0.081312f * b).coerceIn(-0.5f, 0.5f)

            yPlane[i] = y
            uPlane[i] = u
            vPlane[i] = v
        }

        return RawYuvFrame(w, h, yPlane, uPlane, vPlane)
    }
}
