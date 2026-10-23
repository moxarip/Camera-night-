package com.example.model

import android.graphics.Bitmap
import android.net.Uri

enum class CameraMode(val titleAr: String, val subtitleEn: String) {
    LIVE_NIGHT_VISION("رؤية ليلية وفيديو", "Zero-DCE Live & Video"),
    NIGHT_SIGHT_HDR("تصوير ليلي ذكي", "HDR+ Burst Stacking"),
    ASTRO_SEQUATOR("تصوير النجوم", "Sequator & StarStaX")
}

enum class AstroSubMode(val titleAr: String, val descriptionAr: String) {
    DEEP_SKY_STACKING("تكديس السماء العميقة (Sequator)", "محاذاة نقاط النجوم لتعويض دوران الأرض + تجميد الأرضية"),
    STAR_TRAILS("مسارات النجوم (StarStaX)", "دمج Lighten Blending لرسم دوائر ومسارات النجوم المضيئة")
}

data class ProControlsState(
    // Live Night Vision (Zero-DCE) Controls
    val zeroDceGainBoost: Float = 2.4f,          // 1.0f .. 5.0f
    val temporalDenoising: Float = 0.65f,        // 0.0f .. 0.92f
    val zeroDceIterations: Int = 8,              // 4 .. 8 curve iterations
    val gammaCorrection: Float = 0.75f,          // 0.4f .. 1.2f

    // Night Sight (HDR+ Burst) Controls
    val hdrBurstFrameCount: Int = 10,            // 8 .. 15 frames
    val chromaDenoiseStrength: Float = 0.80f,    // 0.0f .. 1.0f
    val lumaSharpness: Float = 0.65f,            // 0.0f .. 1.0f
    val alignmentBlockSize: Int = 16,            // 8, 16, 32 px optical flow block matching

    // Astrophotography (Sequator / StarStaX) Pro Controls
    val astroSubMode: AstroSubMode = AstroSubMode.DEEP_SKY_STACKING,
    val foregroundFreezeEnabled: Boolean = true,
    val shutterSpeedSeconds: Float = 4.0f,       // 0.5s .. 30.0s
    val isoSensitivity: Int = 1600,              // 100 .. 6400
    val focusDistanceDiopters: Float = 0.0f,     // 0.0f = True Infinity Focus
    val targetAstroFrames: Int = 15,             // 5 .. 60 frames
    val autoLightPollutionReduce: Boolean = true
)

data class TripodTelemetry(
    val isTripodStable: Boolean = false,
    val angularVelocityRadSec: Float = 0.0f,
    val linearJitterMeterSec2: Float = 0.0f,
    val stabilityPercentage: Int = 100,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f
)

data class StackingProgressState(
    val isStacking: Boolean = false,
    val mode: CameraMode = CameraMode.NIGHT_SIGHT_HDR,
    val currentFrame: Int = 0,
    val totalFrames: Int = 10,
    val alignedStarsCount: Int = 0,
    val currentDisplacementPx: Float = 0f,
    val estimatedRemainingSeconds: Int = 0,
    val statusMessageAr: String = "",
    val progressivePreviewBitmap: Bitmap? = null,
    val snrImprovementDb: Float = 0f
)

data class ExportedMediaItem(
    val uri: Uri,
    val displayName: String,
    val isVideo: Boolean,
    val timestampMillis: Long,
    val relativePath: String,
    val thumbnailBitmap: Bitmap? = null,
    val resolutionText: String = "",
    val captureDetailsAr: String = ""
)
