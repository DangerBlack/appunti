package org.appunti.app.asr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.appunti.app.data.SAMPLE_RATE
import java.nio.ByteOrder

/**
 * Decodes any audio file Android supports (m4a, aac, mp3, ogg, wav...)
 * into 16 kHz mono float PCM, in chunks, without ever loading it all into memory.
 */
object AudioDecoder {

    /** [source] sets the file to read on the extractor (path or content://). */
    fun decode(source: (MediaExtractor) -> Unit, isCancelled: () -> Boolean, onPcm: (FloatArray) -> Unit) {
        val ex = MediaExtractor()
        source(ex)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track")
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler = LinearResampler(rate, SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (!isCancelled()) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (newRate != rate) { rate = newRate; resampler = LinearResampler(rate, SAMPLE_RATE) }
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(o)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.order(ByteOrder.LITTLE_ENDIAN)
                        val mono: FloatArray = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = out.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            FloatArray(frames) { i -> var s = 0f; for (ch in 0 until channels) s += fb.get(i * channels + ch); s / channels }
                        } else {
                            val sb = out.asShortBuffer()
                            val frames = sb.remaining() / channels
                            FloatArray(frames) { i -> var s = 0f; for (ch in 0 until channels) s += sb.get(i * channels + ch); s / channels / 32768f }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (mono.isNotEmpty()) onPcm(resampler.process(mono))
                    } else {
                        codec.releaseOutputBuffer(o, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }
}

/** Streaming linear resampler: more than good enough for speech down to 16 kHz. */
class LinearResampler(inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val bypass = inRate == outRate
    private var t = 1.0 // position in the buffer [prev, x0, x1, ...]
    private var prev = 0f

    fun process(x: FloatArray): FloatArray {
        if (bypass || x.isEmpty()) return x
        val out = FloatArray(((x.size - t + 1) / step).toInt().coerceAtLeast(0) + 2)
        var n = 0
        while (t < x.size) {
            val i = t.toInt()
            val f = (t - i).toFloat()
            val a = if (i == 0) prev else x[i - 1]
            val b = x[i]
            if (n < out.size) out[n++] = a + (b - a) * f
            t += step
        }
        t -= x.size
        prev = x[x.size - 1]
        return out.copyOf(n)
    }
}
