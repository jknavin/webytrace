package com.artrace.app

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor

/**
 * Turns a series of bitmaps into an H.264 MP4. Every bitmap becomes exactly one
 * frame at [fps], so a photo taken every 2 s plays back at 60x speed.
 *
 * Not thread-safe: call everything from one background thread.
 */
class TimelapseEncoder(
    private val pfd: ParcelFileDescriptor,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30
) {
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private val argb = IntArray(width * height)
    private var track = -1
    private var muxerStarted = false
    private var frames = 0L

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 5).coerceIn(2_000_000, 12_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            try { codec.release() } catch (ignored: Exception) {}
            try { muxer.release() } catch (ignored: Exception) {}
            try { pfd.close() } catch (ignored: Exception) {}
            throw e
        }
    }

    fun addFrame(bitmap: Bitmap) {
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        scaled.getPixels(argb, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        val index = obtainInputIndex()
        val image = codec.getInputImage(index) ?: throw IllegalStateException("No input image")
        fillImage(image, argb, width, height)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, frames * 1_000_000L / fps, 0)
        frames++
        drain(false)
    }

    /** Finishes the file. Returns true if a playable video was written. */
    fun finish(): Boolean {
        var ok = frames > 0
        try {
            if (ok) {
                val index = obtainInputIndex()
                codec.queueInputBuffer(
                    index, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                drain(true)
            }
        } catch (e: Exception) {
            ok = false
        }
        try { codec.stop() } catch (e: Exception) {}
        try { codec.release() } catch (e: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (e: Exception) { ok = false }
        try { muxer.release() } catch (e: Exception) {}
        try { pfd.close() } catch (e: Exception) {}
        return ok && muxerStarted
    }

    private fun obtainInputIndex(): Int {
        var tries = 0
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) return i
            drain(false)
            if (++tries > 500) throw IllegalStateException("Encoder stalled")
        }
    }

    private fun drain(endOfStream: Boolean) {
        var retries = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                if (++retries > 500) return
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
            } else if (index >= 0) {
                val buf = codec.getOutputBuffer(index)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (buf != null && info.size > 0 && muxerStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private fun clamp(v: Int): Byte = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()

    private fun fillImage(image: Image, pixels: IntArray, w: Int, h: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = pixels[j * w + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                yBuf.put(j * yRow + i * yPix, clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16))
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuf.put((j shr 1) * uRow + (i shr 1) * uPix, clamp(u))
                    vBuf.put((j shr 1) * vRow + (i shr 1) * vPix, clamp(v))
                }
            }
        }
    }
}
