package com.example.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AstroSubMode
import com.example.model.CameraMode
import com.example.model.ProControlsState
import com.example.model.TripodTelemetry
import com.example.viewmodel.AstroCameraViewModel
import kotlin.math.roundToInt

@Composable
fun AstroCameraScreen(
    viewModel: AstroCameraViewModel,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit
) {
    val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val proControls by viewModel.proControls.collectAsStateWithLifecycle()
    val isRedNightMode by viewModel.isRedNightMode.collectAsStateWithLifecycle()
    val liveFps by viewModel.liveFps.collectAsStateWithLifecycle()
    val tripodTelemetry by viewModel.tripodTelemetry.collectAsStateWithLifecycle()
    val isRecordingVideo by viewModel.isRecordingVideo.collectAsStateWithLifecycle()
    val recordingDurationSec by viewModel.recordingDurationSec.collectAsStateWithLifecycle()
    val stackingProgress by viewModel.stackingProgress.collectAsStateWithLifecycle()
    val exportedItems by viewModel.exportedItems.collectAsStateWithLifecycle()
    val selectedPreviewItem by viewModel.selectedPreviewItem.collectAsStateWithLifecycle()
    val statusBannerAr by viewModel.statusBannerAr.collectAsStateWithLifecycle()

    var showControlsPanel by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Live OpenGL ES Zero-DCE Viewfinder Surface (30-60 FPS) backed by TextureView
        AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = viewModel.glRenderer
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("zero_dce_gl_viewfinder")
        )

        // Subtle vignette & astronomical horizon grid lines
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.65f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        // 2. Top Telemetry & Red Night Mode Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            TopAstroHudBar(
                liveFps = liveFps,
                tripodTelemetry = tripodTelemetry,
                isRedNightMode = isRedNightMode,
                isRecordingVideo = isRecordingVideo,
                recordingDurationSec = recordingDurationSec,
                showControlsPanel = showControlsPanel,
                onToggleRedNightMode = { viewModel.toggleRedNightMode() },
                onToggleControlsPanel = { showControlsPanel = !showControlsPanel },
                onSwitchCamera = { viewModel.switchCameraLens() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status Banner Pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = statusBannerAr,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center
                )
            }

            // Permission notice card if running without camera permission yet
            if (!hasCameraPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "صلاحيات الكاميرا والميكروفون",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "امنح الصلاحية لربط حساس Camera2 الحقيقي مع معالج Zero-DCE GPU",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.testTag("grant_permissions_button")
                        ) {
                            Text("تفعيل الكاميرا")
                        }
                    }
                }
            }
        }

        // 3. Bottom Controls Dock: Latest Exported Media Bar + Mode Specific Pro Controls + Mode Switcher + Shutter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Latest Exported Media Bar (DCIM/AstroNightCam or Movies/AstroNightCam)
            val latestItem = exportedItems.firstOrNull()
            if (latestItem != null) {
                LatestExportedMediaBar(
                    item = latestItem,
                    onPreviewClicked = { viewModel.openMediaPreviewModal(latestItem) },
                    onOpenGalleryClicked = { viewModel.mediaExporter.openInExternalGallery(latestItem) },
                    onShareClicked = { viewModel.mediaExporter.shareMediaItem(latestItem) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Collapsible Pro Controls Panel tailored to the active mode
            AnimatedVisibility(visible = showControlsPanel) {
                ModeSpecificControlsCard(
                    selectedMode = selectedMode,
                    proControls = proControls,
                    tripodTelemetry = tripodTelemetry,
                    viewModel = viewModel
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Smooth 3-Mode Switcher Pill Bar
            ModeSelectorTabs(
                selectedMode = selectedMode,
                onSelectMode = { viewModel.selectMode(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Shutter & Video Recording Action Row
            ShutterActionRow(
                selectedMode = selectedMode,
                isRecordingVideo = isRecordingVideo,
                isStacking = stackingProgress.isStacking,
                onPrimaryShutter = { viewModel.onPrimaryShutterClicked() },
                onToggleVideoRecording = { viewModel.toggleNightVideoRecording() },
                onCalibrateInfinity = { viewModel.calibrateInfinityFocus() }
            )
        }

        // 4. Live Progressive Stacking Overlay (Night Sight HDR+ & Sequator Astrophotography)
        ProgressiveStackingOverlay(
            stackingState = stackingProgress,
            tripodTelemetry = tripodTelemetry,
            onStopEarlyAndSave = { viewModel.finishAstroStackEarlyAndSave() }
        )

        // 5. Full-screen Media Preview Modal
        val previewItem = selectedPreviewItem
        if (previewItem != null) {
            MediaPreviewDialog(
                item = previewItem,
                onDismiss = { viewModel.openMediaPreviewModal(null) },
                onOpenInGallery = { viewModel.mediaExporter.openInExternalGallery(previewItem) },
                onShare = { viewModel.mediaExporter.shareMediaItem(previewItem) }
            )
        }
    }
}

@Composable
private fun TopAstroHudBar(
    liveFps: Int,
    tripodTelemetry: TripodTelemetry,
    isRedNightMode: Boolean,
    isRecordingVideo: Boolean,
    recordingDurationSec: Int,
    showControlsPanel: Boolean,
    onToggleRedNightMode: () -> Unit,
    onToggleControlsPanel: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // FPS + Tripod Stability Badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${liveFps} FPS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            val tripodColor = if (tripodTelemetry.isTripodStable) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.secondary
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tripodColor.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("tripod_status_badge")
            ) {
                Text(
                    text = if (tripodTelemetry.isTripodStable) {
                        "ثابت ${tripodTelemetry.stabilityPercentage}% • حامل ثلاثي"
                    } else {
                        "باليد ${tripodTelemetry.stabilityPercentage}%"
                    },
                    color = tripodColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Recording Timer if active
        if (isRecordingVideo) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFEF4444).copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FiberManualRecord,
                    contentDescription = "REC",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                val mins = recordingDurationSec / 60
                val secs = recordingDurationSec % 60
                Text(
                    text = String.format("%02d:%02d", mins, secs),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Action Icons: Red Night Mode, Pro Controls Toggle, Switch Camera
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onToggleRedNightMode,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("toggle_red_night_mode_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.RemoveRedEye,
                    contentDescription = "الوضع الأحمر الفلكي",
                    tint = if (isRedNightMode) Color(0xFFFF2A2A) else MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onToggleControlsPanel,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("toggle_pro_controls_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = "إعدادات التحكم الاحترافي",
                    tint = if (showControlsPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onSwitchCamera,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("switch_camera_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = "تبديل الكاميرا",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ModeSpecificControlsCard(
    selectedMode: CameraMode,
    proControls: ProControlsState,
    tripodTelemetry: TripodTelemetry,
    viewModel: AstroCameraViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pro_controls_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            when (selectedMode) {
                CameraMode.LIVE_NIGHT_VISION -> {
                    Text(
                        text = "محرك الرؤية الليلية اللحظية والفيديو (Zero-DCE GPU)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    ControlSliderRow(
                        label = "حساسية الضوء (Gain Boost)",
                        valueText = "${String.format("%.1f", proControls.zeroDceGainBoost)}x",
                        value = proControls.zeroDceGainBoost,
                        valueRange = 1.0f..5.0f,
                        testTag = "slider_zero_dce_gain",
                        onValueChange = { viewModel.updateZeroDceGain(it) }
                    )

                    ControlSliderRow(
                        label = "تقليل الضوضاء الزمني (Temporal Denoise)",
                        valueText = "${(proControls.temporalDenoising * 100).roundToInt()}%",
                        value = proControls.temporalDenoising,
                        valueRange = 0.0f..0.92f,
                        testTag = "slider_temporal_denoise",
                        onValueChange = { viewModel.updateTemporalDenoise(it) }
                    )

                    ControlSliderRow(
                        label = "تكرارات المنحنى العميق (DCE Curves)",
                        valueText = "${proControls.zeroDceIterations} Curves",
                        value = proControls.zeroDceIterations.toFloat(),
                        valueRange = 4f..8f,
                        testTag = "slider_dce_iterations",
                        onValueChange = { viewModel.updateZeroDceIterations(it.roundToInt()) }
                    )
                }

                CameraMode.NIGHT_SIGHT_HDR -> {
                    Text(
                        text = "التصوير الليلي المحوسب (HDR+ Burst Optical Flow)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    ControlSliderRow(
                        label = "حجم الدفعة (RAW/YUV Burst Frames)",
                        valueText = "${proControls.hdrBurstFrameCount} إطاراً",
                        value = proControls.hdrBurstFrameCount.toFloat(),
                        valueRange = 8f..15f,
                        testTag = "slider_hdr_burst_count",
                        onValueChange = { viewModel.updateHdrBurstCount(it.roundToInt()) }
                    )

                    ControlSliderRow(
                        label = "طرد ضوضاء الألوان (Chroma Denoising)",
                        valueText = "${(proControls.chromaDenoiseStrength * 100).roundToInt()}%",
                        value = proControls.chromaDenoiseStrength,
                        valueRange = 0.1f..1.0f,
                        testTag = "slider_chroma_denoise",
                        onValueChange = { viewModel.updateChromaDenoise(it) }
                    )

                    ControlSliderRow(
                        label = "استخراج تفاصيل الإضاءة (Luma HDR Detail)",
                        valueText = "${(proControls.lumaSharpness * 100).roundToInt()}%",
                        value = proControls.lumaSharpness,
                        valueRange = 0.1f..1.0f,
                        testTag = "slider_luma_sharpness",
                        onValueChange = { viewModel.updateLumaSharpness(it) }
                    )
                }

                CameraMode.ASTRO_SEQUATOR -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "محرك الفضاء والنجوم (Sequator / StarStaX)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = if (tripodTelemetry.isTripodStable) "وضع الفضاء الكامل نشط ✓" else "تثبيت الحامل موصى به",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Sub-mode selector: Deep-Sky Stacking (Sequator) vs Star Trails (StarStaX Lighten)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AstroSubMode.entries.forEach { subMode ->
                            val selected = proControls.astroSubMode == subMode
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.updateAstroSubMode(subMode) },
                                label = {
                                    Text(
                                        text = if (subMode == AstroSubMode.DEEP_SKY_STACKING) {
                                            "تكديس النجوم (Sequator)"
                                        } else {
                                            "مسارات النجوم (StarStaX)"
                                        },
                                        fontSize = 11.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chip_astro_${subMode.name.lowercase()}")
                            )
                        }
                    }

                    // Foreground Freeze toggle (Sequator Ground Mask)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تجميد الأرضية (Foreground Freeze) مع تتبع دوران السماء",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = proControls.foregroundFreezeEnabled,
                            onCheckedChange = { viewModel.toggleForegroundFreeze() },
                            modifier = Modifier.testTag("switch_foreground_freeze")
                        )
                    }

                    // Pro Shutter Speed Chips (up to 30 seconds)
                    val shutterPresets = listOf(1.0f, 2.0f, 4.0f, 8.0f, 15.0f, 30.0f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "الغالق:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(shutterPresets) { sec ->
                                val isSelected = kotlin.math.abs(proControls.shutterSpeedSeconds - sec) < 0.2f
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { viewModel.updateShutterSpeed(sec) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("shutter_preset_${sec.toInt()}s")
                                ) {
                                    Text(
                                        text = "${sec.toInt()}s",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Pro ISO Presets (100 .. 6400)
                    val isoPresets = listOf(200, 400, 800, 1600, 3200, 6400)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ISO:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(isoPresets) { iso ->
                                val isSelected = proControls.isoSensitivity == iso
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.secondary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { viewModel.updateIsoSensitivity(iso) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("iso_preset_$iso")
                                ) {
                                    Text(
                                        text = "$iso",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSecondary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    ControlSliderRow(
                        label = "التركيز البؤري للنجوم (0.00 = اللانهاية ∞)",
                        valueText = if (proControls.focusDistanceDiopters <= 0.05f) "∞ Infinity"
                        else "${String.format("%.2f", proControls.focusDistanceDiopters)} D",
                        value = proControls.focusDistanceDiopters,
                        valueRange = 0.0f..5.0f,
                        testTag = "slider_infinity_focus",
                        onValueChange = { viewModel.updateFocusDistance(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .testTag(testTag)
        )
    }
}

@Composable
private fun ModeSelectorTabs(
    selectedMode: CameraMode,
    onSelectMode: (CameraMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(4.dp)
            .testTag("mode_selector_bar"),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        CameraMode.entries.forEach { mode ->
            val isSelected = mode == selectedMode
            val icon = when (mode) {
                CameraMode.LIVE_NIGHT_VISION -> Icons.Filled.NightlightRound
                CameraMode.NIGHT_SIGHT_HDR -> Icons.Filled.AutoAwesome
                CameraMode.ASTRO_SEQUATOR -> Icons.Filled.ShieldMoon
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelectMode(mode) }
                    .padding(horizontal = 6.dp)
                    .testTag("mode_tab_${mode.name.lowercase()}")
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = mode.titleAr,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = mode.titleAr,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ShutterActionRow(
    selectedMode: CameraMode,
    isRecordingVideo: Boolean,
    isStacking: Boolean,
    onPrimaryShutter: () -> Unit,
    onToggleVideoRecording: () -> Unit,
    onCalibrateInfinity: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Left Secondary Action: Video Recording in Night Vision mode OR Infinity Focus Calibration in Astro/HDR
        if (selectedMode == CameraMode.LIVE_NIGHT_VISION) {
            Button(
                onClick = onToggleVideoRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecordingVideo) Color(0xFFEF4444)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .height(52.dp)
                    .testTag("night_video_record_button")
            ) {
                Icon(
                    imageVector = if (isRecordingVideo) Icons.Filled.Stop else Icons.Filled.Videocam,
                    contentDescription = "تسجيل فيديو ليلي",
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRecordingVideo) "إيقاف الفيديو" else "فيديو ليلي",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        } else {
            Button(
                onClick = onCalibrateInfinity,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .height(52.dp)
                    .testTag("infinity_focus_calibrate_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = "معايرة اللانهاية",
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "بؤرة النجوم ∞",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Center Primary Shutter Button (76.dp tactile astro trigger)
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isStacking -> MaterialTheme.colorScheme.error
                        selectedMode == CameraMode.ASTRO_SEQUATOR -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                .clickable { onPrimaryShutter() }
                .testTag("primary_shutter_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isStacking -> Icons.Filled.Stop
                    selectedMode == CameraMode.NIGHT_SIGHT_HDR -> Icons.Filled.AutoAwesome
                    selectedMode == CameraMode.ASTRO_SEQUATOR -> Icons.Filled.ShieldMoon
                    else -> Icons.Filled.CameraAlt
                },
                contentDescription = "التقاط",
                tint = Color(0xFF050B16),
                modifier = Modifier.size(32.dp)
            )
        }

        // Right Badge showing active pipeline engine
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = when (selectedMode) {
                    CameraMode.LIVE_NIGHT_VISION -> "Zero-DCE"
                    CameraMode.NIGHT_SIGHT_HDR -> "HDR+ Stack"
                    CameraMode.ASTRO_SEQUATOR -> "Sequator"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Camera2 + GL",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
