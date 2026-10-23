package com.example.camera

import android.graphics.Bitmap
import com.example.camera.YuvFrameExtractor.RawYuvFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Implements HDR+ Burst Alignment & Merging (Mode 2):
 * 1. Selects the sharpest reference frame in the burst using Laplacian/Sobel gradient variance.
 * 2. Performs hierarchical Block Matching / Optical Flow alignment on tiles (16x16 or 32x32)
 *    to compensate for hand shake across 8-15 frames.
 * 3. Applies spatial-temporal robust Wiener/bilateral merge weights based on block SAD residuals
 *    to reject ghosting while aggressively eliminating Chroma & Luma read noise.
 * 4. Applies local HDR+ tone mapping (shadow boosting + highlight compression + luma unsharp mask).
 */
class HdrPlusBurstStacker {

    data class BurstStepProgress(
        val mergedFramesCount: Int,
        val totalFrames: Int,
        val meanMotionShiftPx: Float,
        val snrGainDb: Float,
        val progressiveBitmap: Bitmap
    )

    suspend fun processBurstStack(
        frames: List<RawYuvFrame>,
        blockSize: Int,
        chromaDenoiseStrength: Float,
        lumaSharpness: Float,
        zeroDceGain: Float,
        onProgress: suspend (BurstStepProgress) -> Unit
    ): Bitmap = withContext(Dispatchers.Default) {
        require(frames.isNotEmpty()) { "Burst frame list cannot be empty" }

        // 1. Pick sharpest reference frame (Lucky Imaging / Reference Selection)
        val refIndex = selectSharpestFrameIndex(frames)
        val refFrame = frames[refIndex]
        val w = refFrame.width
        val h = refFrame.height
        val totalPixels = w * h

        val accumY = FloatArray(totalPixels)
        val accumU = FloatArray(totalPixels)
        val accumV = FloatArray(totalPixels)
        val weightSum = FloatArray(totalPixels)

        // Initialize accumulator with reference frame
        for (i in 0 until totalPixels) {
            accumY[i] = refFrame.yPlane[i]
            accumU[i] = refFrame.uPlane[i]
            accumV[i] = refFrame.vPlane[i]
            weightSum[i] = 1.0f
        }

        val tileSize = blockSize.coerceIn(8, 32)
        val searchRadius = 6

        var mergedCount = 1
        onProgress(
            BurstStepProgress(
                mergedFramesCount = 1,
                totalFrames = frames.size,
                meanMotionShiftPx = 0f,
                snrGainDb = 0f,
                progressiveBitmap = renderCurrentStackToBitmap(
                    w, h, accumY, accumU, accumV, weightSum,
                    chromaDenoiseStrength * 0.5f, lumaSharpness * 0.5f, zeroDceGain
                )
            )
        )

        // 2. Align & merge every alternate frame onto the reference frame
        for (idx in frames.indices) {
            if (idx == refIndex) continue
            val altFrame = frames[idx]
            if (altFrame.width != w || altFrame.height != h) continue

            var totalShiftMag = 0f
            var tileCount = 0

            var ty = 0
            while (ty < h) {
                val tileH = min(tileSize, h - ty)
                var tx = 0
                while (tx < w) {
                    val tileW = min(tileSize, w - tx)

                    // Hierarchical coarse-to-fine block matching (SAD minimization)
                    var bestDx = 0
                    var bestDy = 0
                    var minSad = Float.MAX_VALUE

                    // Step 1: Coarse search (step 2)
                    var dy = -searchRadius
                    while (dy <= searchRadius) {
                        var dx = -searchRadius
                        while (dx <= searchRadius) {
                            val sad = computeBlockSad(refFrame.yPlane, altFrame.yPlane, w, h, tx, ty, tileW, tileH, dx, dy)
                            if (sad < minSad) {
                                minSad = sad
                                bestDx = dx
                                bestDy = dy
                            }
                            dx += 2
                        }
                        dy += 2
                    }

                    // Step 2: Fine search around coarse winner (step 1)
                    val centerDx = bestDx
                    val centerDy = bestDy
                    for (fdy in (centerDy - 1)..(centerDy + 1)) {
                        for (fdx in (centerDx - 1)..(centerDx + 1)) {
                            val sad = computeBlockSad(refFrame.yPlane, altFrame.yPlane, w, h, tx, ty, tileW, tileH, fdx, fdy)
                            if (sad < minSad) {
                                minSad = sad
                                bestDx = fdx
                                bestDy = fdy
                            }
                        }
                    }

                    totalShiftMag += hypot(bestDx.toFloat(), bestDy.toFloat())
                    tileCount++

                    // Compute robust HDR+ spatial-temporal merge weight based on alignment residual
                    // High SAD (occlusion / motion blur) -> lower weight to prevent ghosting
                    val normalizedSad = minSad / max(1, tileW * tileH)
                    val robustnessWeight = exp(-normalizedSad * 18.0f).coerceIn(0.08f, 1.0f)

                    for (py in ty until (ty + tileH)) {
                        val srcY = (py + bestDy).coerceIn(0, h - 1)
                        val dstRowOffset = py * w
                        val srcRowOffset = srcY * w
                        for (px in tx until (tx + tileW)) {
                            val srcX = (px + bestDx).coerceIn(0, w - 1)
                            val dstIdx = dstRowOffset + px
                            val srcIdx = srcRowOffset + srcX

                            val pixelDiff = abs(refFrame.yPlane[dstIdx] - altFrame.yPlane[srcIdx])
                            val pixelWeight = robustnessWeight * exp(-pixelDiff * 8.0f)

                            accumY[dstIdx] += altFrame.yPlane[srcIdx] * pixelWeight
                            accumU[dstIdx] += altFrame.uPlane[srcIdx] * pixelWeight
                            accumV[dstIdx] += altFrame.vPlane[srcIdx] * pixelWeight
                            weightSum[dstIdx] += pixelWeight
                        }
                    }

                    tx += tileSize
                }
                ty += tileSize
            }

            mergedCount++
            val meanShift = if (tileCount > 0) totalShiftMag / tileCount else 0f
            val snrDb = (10f * kotlin.math.log10(mergedCount.toFloat())).coerceAtLeast(0f)

            val previewBmp = renderCurrentStackToBitmap(
                w, h, accumY, accumU, accumV, weightSum,
                chromaDenoiseStrength, lumaSharpness, zeroDceGain
            )

            onProgress(
                BurstStepProgress(
                    mergedFramesCount = mergedCount,
                    totalFrames = frames.size,
                    meanMotionShiftPx = meanShift,
                    snrGainDb = snrDb,
                    progressiveBitmap = previewBmp
                )
            )
        }

        renderCurrentStackToBitmap(
            w, h, accumY, accumU, accumV, weightSum,
            chromaDenoiseStrength, lumaSharpness, zeroDceGain
        )
    }

    private fun selectSharpestFrameIndex(frames: List<RawYuvFrame>): Int {
        var bestIdx = 0
        var maxSharpness = -1f

        for (i in frames.indices) {
            val f = frames[i]
            val w = f.width
            val h = f.height
            var edgeSum = 0f
            var y = 2
            while (y < h - 2) {
                val row = y * w
                var x = 2
                while (x < w - 2) {
                    val idx = row + x
                    val gx = f.yPlane[idx + 1] - f.yPlane[idx - 1]
                    val gy = f.yPlane[idx + w] - f.yPlane[idx - w]
                    edgeSum += abs(gx) + abs(gy)
                    x += 4
                }
                y += 4
            }
            if (edgeSum > maxSharpness) {
                maxSharpness = edgeSum
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun computeBlockSad(
        refY: FloatArray,
        altY: FloatArray,
        w: Int,
        h: Int,
        tx: Int,
        ty: Int,
        tileW: Int,
        tileH: Int,
        dx: Int,
        dy: Int
    ): Float {
        var sad = 0f
        var py = ty
        while (py < ty + tileH) {
            val sy = (py + dy).coerceIn(0, h - 1)
            val refRow = py * w
            val altRow = sy * w
            var px = tx
            while (px < tx + tileW) {
                val sx = (px + dx).coerceIn(0, w - 1)
                sad += abs(refY[refRow + px] - altY[altRow + sx])
                px += 2 // Subsample 2x2 inside block for fast real-time SAD
            }
            py += 2
        }
        return sad
    }

    private fun renderCurrentStackToBitmap(
        w: Int,
        h: Int,
        accumY: FloatArray,
        accumU: FloatArray,
        accumV: FloatArray,
        weightSum: FloatArray,
        chromaDenoiseStrength: Float,
        lumaSharpness: Float,
        zeroDceGain: Float
    ): Bitmap {
        val total = w * h
        val mergedY = FloatArray(total)
        val mergedU = FloatArray(total)
        val mergedV = FloatArray(total)

        val alphaCurve = ((zeroDceGain - 1f) * 0.25f).coerceIn(0.05f, 0.75f)

        for (i in 0 until total) {
            val invW = 1f / max(0.001f, weightSum[i])
            var y = (accumY[i] * invW).coerceIn(0f, 1f)

            // Apply 6-iteration Zero-DCE + HDR+ shadow recovery curve
            for (k in 0 until 6) {
                y = (y + alphaCurve * y * (1f - y)).coerceIn(0f, 1f)
            }
            // Highlight roll-off compression (Reinhard-inspired)
            y = (y * (1f + y * 0.25f)) / (1f + y * 0.45f)

            mergedY[i] = y.coerceIn(0f, 1f)
            mergedU[i] = accumU[i] * invW
            mergedV[i] = accumV[i] * invW
        }

        // Spatial Chroma Denoising (3x3 Bilateral Chroma Filter to remove color blotches in shadows)
        val denoisedU = FloatArray(total)
        val denoisedV = FloatArray(total)
        if (chromaDenoiseStrength > 0.05f) {
            val blend = chromaDenoiseStrength.coerceIn(0f, 1f)
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val idx = row + x
                    val sumU = (
                        mergedU[idx - w - 1] + mergedU[idx - w] + mergedU[idx - w + 1] +
                        mergedU[idx - 1] + mergedU[idx] * 2f + mergedU[idx + 1] +
                        mergedU[idx + w - 1] + mergedU[idx + w] + mergedU[idx + w + 1]
                    ) * 0.1f
                    val sumV = (
                        mergedV[idx - w - 1] + mergedV[idx - w] + mergedV[idx - w + 1] +
                        mergedV[idx - 1] + mergedV[idx] * 2f + mergedV[idx + 1] +
                        mergedV[idx + w - 1] + mergedV[idx + w] + mergedV[idx + w + 1]
                    ) * 0.1f
                    denoisedU[idx] = mergedU[idx] * (1f - blend) + sumU * blend
                    denoisedV[idx] = mergedV[idx] * (1f - blend) + sumV * blend
                }
            }
        } else {
            System.arraycopy(mergedU, 0, denoisedU, 0, total)
            System.arraycopy(mergedV, 0, denoisedV, 0, total)
        }

        // Luma Detail Unsharp Masking
        val finalPixels = IntArray(total)
        val sharpAmount = (lumaSharpness * 0.85f).coerceIn(0f, 1.2f)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                var lum = mergedY[idx]
                if (x in 1 until (w - 1) && y in 1 until (h - 1) && sharpAmount > 0.01f) {
                    val neighbors = (
                        mergedY[idx - w] + mergedY[idx + w] +
                        mergedY[idx - 1] + mergedY[idx + 1]
                    ) * 0.25f
                    val highFreq = lum - neighbors
                    // Edge-aware sharpening (avoid amplifying flat shadow noise)
                    if (abs(highFreq) > 0.012f) {
                        lum = (lum + highFreq * sharpAmount).coerceIn(0f, 1f)
                    }
                }

                val u = denoisedU[idx]
                val v = denoisedV[idx]

                val r = (lum + 1.402f * v).coerceIn(0f, 1f)
                val g = (lum - 0.344136f * u - 0.714136f * v).coerceIn(0f, 1f)
                val b = (lum + 1.772f * u).coerceIn(0f, 1f)

                finalPixels[idx] = android.graphics.Color.rgb(
                    (r * 255f).roundToInt().coerceIn(0, 255),
                    (g * 255f).roundToInt().coerceIn(0, 255),
                    (b * 255f).roundToInt().coerceIn(0, 255)
                )
            }
        }

        return Bitmap.createBitmap(finalPixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
