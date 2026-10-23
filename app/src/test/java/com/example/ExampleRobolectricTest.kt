package com.example

import com.example.camera.HdrPlusBurstStacker
import com.example.camera.SequatorAstroEngine
import com.example.camera.YuvFrameExtractor.RawYuvFrame
import com.example.model.AstroSubMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

    @Test
    fun hdrPlusBurstStacker_alignsAndDenoisesBurstFrames() = runTest {
        val w = 64
        val h = 64
        val frames = (0 until 8).map { idx ->
            val y = FloatArray(w * h) { i ->
                val px = i % w
                val py = i / w
                if ((px + idx % 2) in 20..40 && (py + idx % 2) in 20..40) 0.75f else 0.12f
            }
            val u = FloatArray(w * h) { 0.02f }
            val v = FloatArray(w * h) { -0.01f }
            RawYuvFrame(w, h, y, u, v)
        }

        val stacker = HdrPlusBurstStacker()
        var stepsCount = 0
        val resultBmp = stacker.processBurstStack(
            frames = frames,
            blockSize = 16,
            chromaDenoiseStrength = 0.8f,
            lumaSharpness = 0.6f,
            zeroDceGain = 2.2f
        ) { progress ->
            stepsCount = progress.mergedFramesCount
        }

        assertNotNull(resultBmp)
        assertEquals(w, resultBmp.width)
        assertEquals(h, resultBmp.height)
        assertEquals(8, stepsCount)
    }

    @Test
    fun sequatorAstroEngine_stacksStarsAndFreezesForeground() = runTest {
        val w = 80
        val h = 80
        val engine = SequatorAstroEngine()
        engine.resetSession()

        for (frameIdx in 1..4) {
            val yPlane = FloatArray(w * h) { 0.05f }
            // Add synthetic bright stars in upper sky
            yPlane[15 * w + 20 + frameIdx] = 0.95f
            yPlane[22 * w + 50 + frameIdx] = 0.92f
            yPlane[30 * w + 35 + frameIdx] = 0.90f
            val uPlane = FloatArray(w * h)
            val vPlane = FloatArray(w * h)

            val step = engine.processNextAstroFrame(
                frame = RawYuvFrame(w, h, yPlane, uPlane, vPlane),
                subMode = AstroSubMode.DEEP_SKY_STACKING,
                foregroundFreezeEnabled = true,
                autoLightPollutionReduce = true,
                zeroDceGain = 2.4f,
                targetFrames = 4
            )
            assertEquals(frameIdx, step.stackedFrames)
            assertTrue(step.detectedStarsCount > 0)
            assertNotNull(step.progressiveBitmap)
        }
    }
}
