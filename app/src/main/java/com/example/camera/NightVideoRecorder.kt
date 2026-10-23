package com.example.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Records Zero-DCE Night Vision video + clear AAC Audio with strict presentationTimeUs (PTS)
 * calculated directly from actual monotonic system time (`(System.nanoTime() - recordingStartNs) / 1000L`).
 *
 * Uses asynchronous worker encoding of RGBA/YUV frames onto H.264 MediaCodec + AAC AudioRecord
 * so it never blocks the UI thread or triggers EGL context conflicts on emulator/device GPUs.
 */
class NightVideoRecorder(
    private val outputFile: File,
    val width: Int,
    val height: Int
) {
    private data class TimestampedFrame(
        val rgbaBytes: ByteArray,
        val ptsUs: Long
    )

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val muxerLock = Object()

    private val isRecording = AtomicBoolean(false)
    private var recordingStartNs: Long = 0L
    private var lastVideoPtsUs: Long = 0L
    private var lastAudioPtsUs: Long = 0L

    private val frameQueue = LinkedBlockingQueue<TimestampedFrame>(12)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    val alignedWidth = ((width / 16) * 16).coerceIn(320, 720)
    val alignedHeight = ((height / 16) * 16).coerceIn(320, 1280)

    fun prepareAndStart(): Boolean {
        return try {
            recordingStartNs = System.nanoTime()
            lastVideoPtsUs = 0L
            lastAudioPtsUs = 0L
            frameQueue.clear()

            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                alignedWidth,
                alignedHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val vEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            vEncoder.start()
            videoEncoder = vEncoder

            val sampleRate = 44100
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aEncoder.start()
            audioEncoder = aEncoder

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            isRecording.set(true)
            startVideoEncodingLoop()
            startAudioCaptureLoop(sampleRate)
            true
        } catch (_: Exception) {
            releaseResources()
            false
        }
    }

    /**
     * Submits a rendered Zero-DCE RGBA frame with strict monotonic wall-clock presentationTimeUs.
     */
    fun offerFrame(rgbaBytes: ByteArray) {
        if (!isRecording.get()) return
        val ptsUs = ((System.nanoTime() - recordingStartNs) / 1000L).coerceAtLeast(1000L)
        frameQueue.offer(TimestampedFrame(rgbaBytes, ptsUs))
    }

    fun getElapsedSeconds(): Int {
        if (!isRecording.get() || recordingStartNs == 0L) return 0
        return ((System.nanoTime() - recordingStartNs) / 1_000_000_000L).toInt().coerceAtLeast(1)
    }

    fun stopAndFinalize(): Int {
        val durationSec = getElapsedSeconds().coerceAtLeast(1)
        if (!isRecording.getAndSet(false)) return durationSec

        try {
            videoThread?.join(1200)
        } catch (_: Exception) {
        }
        try {
            audioThread?.join(1000)
        } catch (_: Exception) {
        }

        releaseResources()
        return durationSec
    }

    private fun startVideoEncodingLoop() {
        videoThread = Thread {
            val vEncoder = videoEncoder ?: return@Thread
            val yuvBuffer = ByteArray(alignedWidth * alignedHeight * 3 / 2)

            while (isRecording.get() || frameQueue.isNotEmpty()) {
                try {
                    val frame = frameQueue.poll(40, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        val inIdx = vEncoder.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inputBuf = vEncoder.getInputBuffer(inIdx)
                            if (inputBuf != null) {
                                convertRgbaToYuv420Planar(
                                    rgba = frame.rgbaBytes,
                                    yuvOut = yuvBuffer,
                                    w = alignedWidth,
                                    h = alignedHeight
                                )
                                inputBuf.clear()
                                val bytesToWrite = min(inputBuf.remaining(), yuvBuffer.size)
                                inputBuf.put(yuvBuffer, 0, bytesToWrite)

                                val strictPtsUs = max(frame.ptsUs, lastVideoPtsUs + 33_333L)
                                vEncoder.queueInputBuffer(inIdx, 0, bytesToWrite, strictPtsUs, 0)
                            }
                        }
                    }
                    drainVideoEncoder(vEncoder, endOfStream = false)
                } catch (_: Exception) {
                    break
                }
            }

            try {
                val inIdx = vEncoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val endPts = ((System.nanoTime() - recordingStartNs) / 1000L).coerceAtLeast(lastVideoPtsUs + 1000L)
                    vEncoder.queueInputBuffer(inIdx, 0, 0, endPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainVideoEncoder(vEncoder, endOfStream = true)
            } catch (_: Exception) {
            }
        }.apply {
            name = "AstroNightVideoEncoder"
            start()
        }
    }

    private fun convertRgbaToYuv420Planar(rgba: ByteArray, yuvOut: ByteArray, w: Int, h: Int) {
        val frameSize = w * h
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + (frameSize shr 2)

        for (j in 0 until h) {
            // Flip vertically if coming from GL readPixels, or read top-down
            val rowOffset = j * w * 4
            for (i in 0 until w) {
                val p = rowOffset + i * 4
                if (p + 2 >= rgba.size) continue
                val r = rgba[p].toInt() and 0xFF
                val g = rgba[p + 1].toInt() and 0xFF
                val b = rgba[p + 2].toInt() and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuvOut[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (uIndex < yuvOut.size) yuvOut[uIndex++] = u.coerceIn(0, 255).toByte()
                    if (vIndex < yuvOut.size) yuvOut[vIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun drainVideoEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        var loopGuard = 0
        while (loopGuard++ < 35) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 8_000L else 0L)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (!muxerStarted) {
                        videoTrackIndex = mediaMuxer?.addTrack(encoder.outputFormat) ?: -1
                        checkStartMuxerLocked()
                    }
                }
            } else if (status >= 0) {
                val encodedData: ByteBuffer? = encoder.getOutputBuffer(status)
                if (encodedData != null && bufferInfo.size > 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted && videoTrackIndex >= 0) {
                                val monotonicPts = max(bufferInfo.presentationTimeUs, lastVideoPtsUs + 1000L)
                                bufferInfo.presentationTimeUs = monotonicPts
                                lastVideoPtsUs = monotonicPts

                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop(sampleRate: Int) {
        audioThread = Thread {
            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize * 2
                )
                if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.startRecording()
                }
            } catch (_: Exception) {
                audioRecord = null
            }

            val pcmBuffer = ByteArray(2048)
            val aEncoder = audioEncoder

            while (isRecording.get() && aEncoder != null) {
                try {
                    val bytesRead = if (audioRecord != null && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    } else {
                        java.util.Arrays.fill(pcmBuffer, 0.toByte())
                        Thread.sleep(20)
                        pcmBuffer.size
                    }

                    if (bytesRead > 0) {
                        val inIdx = aEncoder.dequeueInputBuffer(5_000L)
                        if (inIdx >= 0) {
                            val inputBuf = aEncoder.getInputBuffer(inIdx)
                            inputBuf?.clear()
                            inputBuf?.put(pcmBuffer, 0, bytesRead)
                            val ptsUs = ((System.nanoTime() - recordingStartNs) / 1000L).coerceAtLeast(lastAudioPtsUs + 500L)
                            aEncoder.queueInputBuffer(inIdx, 0, bytesRead, ptsUs, 0)
                        }
                    }

                    drainAudioEncoder(aEncoder, endOfStream = false)
                } catch (_: Exception) {
                    break
                }
            }

            try {
                if (aEncoder != null) {
                    val inIdx = aEncoder.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val ptsUs = ((System.nanoTime() - recordingStartNs) / 1000L).coerceAtLeast(lastAudioPtsUs + 500L)
                        aEncoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    drainAudioEncoder(aEncoder, endOfStream = true)
                }
            } catch (_: Exception) {
            }

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {
            }
        }.apply {
            name = "AstroNightAudioRecorder"
            start()
        }
    }

    private fun drainAudioEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        var loopCount = 0
        while (loopCount++ < 25) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 5_000L else 0L)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (!muxerStarted) {
                        audioTrackIndex = mediaMuxer?.addTrack(encoder.outputFormat) ?: -1
                        checkStartMuxerLocked()
                    }
                }
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)
                if (encodedData != null && bufferInfo.size > 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted && audioTrackIndex >= 0) {
                                val monotonicPts = bufferInfo.presentationTimeUs.coerceAtLeast(lastAudioPtsUs + 500L)
                                bufferInfo.presentationTimeUs = monotonicPts
                                lastAudioPtsUs = monotonicPts
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    private fun checkStartMuxerLocked() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            mediaMuxer?.start()
            muxerStarted = true
        }
    }

    private fun releaseResources() {
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (_: Exception) {
        }
        videoEncoder = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        audioEncoder = null

        synchronized(muxerLock) {
            try {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()
            } catch (_: Exception) {
            }
            mediaMuxer = null
            muxerStarted = false
        }
    }
}
