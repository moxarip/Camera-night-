package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.model.ExportedMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles direct, atomic exporting of Night Sight HDR+ photos, Astrophotography stacked images,
 * and Zero-DCE Night Vision videos into the public Android Gallery via MediaStore API:
 * - Photos -> DCIM/AstroNightCam
 * - Videos -> Movies/AstroNightCam
 * Uses IS_PENDING = 1 -> 0 on Android Q+ so media immediately appears in the phone's Gallery.
 */
class MediaStoreExporter(private val context: Context) {

    companion object {
        const val PHOTO_RELATIVE_PATH = "DCIM/AstroNightCam"
        const val VIDEO_RELATIVE_PATH = "Movies/AstroNightCam"
    }

    suspend fun exportBitmapToGallery(
        bitmap: Bitmap,
        modePrefix: String,
        captureDetailsAr: String
    ): ExportedMediaItem? = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "${modePrefix}_${timestamp}.jpg"
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collectionUri, contentValues) ?: return@withContext null

        try {
            resolver.openOutputStream(itemUri)?.use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, outStream)
                outStream.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
            }

            // Create scaled thumbnail for quick in-app bar & modal preview
            val thumbWidth = 360
            val thumbHeight = (bitmap.height.toFloat() / bitmap.width.toFloat() * thumbWidth).toInt().coerceAtLeast(180)
            val thumbnail = Bitmap.createScaledBitmap(bitmap, thumbWidth, thumbHeight, true)

            ExportedMediaItem(
                uri = itemUri,
                displayName = displayName,
                isVideo = false,
                timestampMillis = System.currentTimeMillis(),
                relativePath = PHOTO_RELATIVE_PATH,
                thumbnailBitmap = thumbnail,
                resolutionText = "${bitmap.width}×${bitmap.height}",
                captureDetailsAr = captureDetailsAr
            )
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    suspend fun exportVideoFileToGallery(
        tempVideoFile: File,
        width: Int,
        height: Int,
        durationSeconds: Int,
        captureDetailsAr: String,
        previewThumbnail: Bitmap?
    ): ExportedMediaItem? = withContext(Dispatchers.IO) {
        if (!tempVideoFile.exists() || tempVideoFile.length() == 0L) return@withContext null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "ZeroDCE_NightVideo_${timestamp}.mp4"
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.WIDTH, width)
            put(MediaStore.Video.Media.HEIGHT, height)
            put(MediaStore.Video.Media.DURATION, durationSeconds * 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "AstroNightCam"
                )
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val outFile = File(moviesDir, displayName)
                put(MediaStore.Video.Media.DATA, outFile.absolutePath)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collectionUri, contentValues) ?: return@withContext null

        try {
            resolver.openOutputStream(itemUri)?.use { outStream ->
                FileInputStream(tempVideoFile).use { inStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        outStream.write(buffer, 0, bytesRead)
                    }
                    outStream.flush()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
            } else {
                MediaScannerConnection.scanFile(context, arrayOf(itemUri.path), arrayOf("video/mp4"), null)
            }

            tempVideoFile.delete()

            ExportedMediaItem(
                uri = itemUri,
                displayName = displayName,
                isVideo = true,
                timestampMillis = System.currentTimeMillis(),
                relativePath = VIDEO_RELATIVE_PATH,
                thumbnailBitmap = previewThumbnail,
                resolutionText = "${width}×${height} • ${durationSeconds}s",
                captureDetailsAr = captureDetailsAr
            )
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    fun openInExternalGallery(item: ExportedMediaItem) {
        val mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "فتح في المعرض").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    fun shareMediaItem(item: ExportedMediaItem) {
        val mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, item.uri)
            putExtra(Intent.EXTRA_TEXT, "تم الالتقاط بواسطة تطبيق AstroNightCam (${item.captureDetailsAr})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "مشاركة عبر").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (_: Exception) {
        }
    }
}
