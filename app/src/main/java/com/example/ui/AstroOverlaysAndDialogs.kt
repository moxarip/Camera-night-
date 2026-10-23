package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.CameraMode
import com.example.model.ExportedMediaItem
import com.example.model.StackingProgressState
import com.example.model.TripodTelemetry

/**
 * Live Progressive Stacking Preview & Real-Time Astrophotography / HDR+ Counter Overlay.
 * Displays:
 * - Merged frames counter (current / total)
 * - Remaining time countdown (seconds)
 * - Matched stars count (Sequator Star Alignment) or Optical Flow motion shift
 * - Tripod stability percentage
 * - Live progressive stacked image clarifying in real time
 */
@Composable
fun ProgressiveStackingOverlay(
    stackingState: StackingProgressState,
    tripodTelemetry: TripodTelemetry,
    onStopEarlyAndSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = stackingState.isStacking,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("progressive_stacking_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "تكديس حي",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (stackingState.mode == CameraMode.ASTRO_SEQUATOR) {
                                "المعاينة الحية لتكديس النجوم (Sequator Live)"
                            } else {
                                "المعاينة الحية لتكديس HDR+ الليلي"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Live Progressive Stacking Image Preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF050811))
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = stackingState.progressivePreviewBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Progressive Stacked Preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = "جاري تجميع الفوتونات ومحاذاة الإطار المرجعي...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp)
                            )
                        }

                        // SNR Badge in corner
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "SNR +${String.format("%.1f", stackingState.snrImprovementDb)} dB",
                                color = MaterialTheme.colorScheme.tertiary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 4 Real-time Telemetry Counters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StackMetricBadge(
                            label = "الإطارات المدمجة",
                            value = "${stackingState.currentFrame} / ${stackingState.totalFrames}"
                        )
                        StackMetricBadge(
                            label = "الوقت المتبقي",
                            value = "${stackingState.estimatedRemainingSeconds} ثانية"
                        )
                        StackMetricBadge(
                            label = if (stackingState.mode == CameraMode.ASTRO_SEQUATOR) "النجوم المتطابقة" else "تعويض الاهتزاز",
                            value = if (stackingState.mode == CameraMode.ASTRO_SEQUATOR) {
                                "${stackingState.alignedStarsCount} ★"
                            } else {
                                "${String.format("%.1f", stackingState.currentDisplacementPx)} px"
                            }
                        )
                        StackMetricBadge(
                            label = "مؤشر الثبات",
                            value = "${tripodTelemetry.stabilityPercentage}%"
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val progressFraction = if (stackingState.totalFrames > 0) {
                        (stackingState.currentFrame.toFloat() / stackingState.totalFrames.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stackingState.statusMessageAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (stackingState.mode == CameraMode.ASTRO_SEQUATOR) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onStopEarlyAndSave,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("stop_stacking_save_button")
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("إيقاف التكديس وحفظ الصورة الفلكية الآن", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StackMetricBadge(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Floating Action Bar displaying the latest exported photo/video in DCIM/AstroNightCam or Movies/AstroNightCam
 * with direct buttons for: Preview (معاينة), Open in Gallery (فتح), and Share (مشاركة).
 */
@Composable
fun LatestExportedMediaBar(
    item: ExportedMediaItem,
    onPreviewClicked: () -> Unit,
    onOpenGalleryClicked: () -> Unit,
    onShareClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("latest_exported_media_bar"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with video badge if applicable
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { onPreviewClicked() }
                    .testTag("media_thumbnail_box"),
                contentAlignment = Alignment.Center
            ) {
                if (item.thumbnailBitmap != null) {
                    Image(
                        bitmap = item.thumbnailBitmap.asImageBitmap(),
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                if (item.isVideo) {
                    Icon(
                        imageVector = Icons.Filled.PlayCircleFilled,
                        contentDescription = "فيديو",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.relativePath} • ${item.resolutionText}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 3 Required Action Buttons: معاينة (Preview), فتح (Open), مشاركة (Share)
            IconButton(
                onClick = onPreviewClicked,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_preview_media")
            ) {
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = "معاينة",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onOpenGalleryClicked,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_open_gallery")
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = "فتح في المعرض",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }

            IconButton(
                onClick = onShareClicked,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_share_media")
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "مشاركة",
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/**
 * Full-screen In-App Media Preview Dialog with metadata, Open in Gallery, and Share actions.
 */
@Composable
fun MediaPreviewDialog(
    item: ExportedMediaItem,
    onDismiss: () -> Unit,
    onOpenInGallery: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "محفوظ في: ${item.relativePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .testTag("close_preview_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "إغلاق",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF070A12)),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.thumbnailBitmap != null) {
                        Image(
                            bitmap = item.thumbnailBitmap.asImageBitmap(),
                            contentDescription = item.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "${item.captureDetailsAr} • ${item.resolutionText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenInGallery,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("dialog_open_gallery_button")
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("فتح في المعرض")
                    }

                    Button(
                        onClick = onShare,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("dialog_share_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مشاركة الملف")
                    }
                }
            }
        }
    }
}
