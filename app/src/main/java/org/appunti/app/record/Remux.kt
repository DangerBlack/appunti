package org.appunti.app.record

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * While recording, audio is saved as AAC "ADTS": every frame is self-contained,
 * so even if the app dies abruptly the file stays readable up to the last second.
 * When recording ends we repackage it (without re-encoding) into M4A, which plays
 * and seeks better in any player.
 *
 * @return duration in milliseconds, or -1 if the conversion failed.
 */
object Remux {
    fun adtsToM4a(src: File, dst: File): Long {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            if (ex.trackCount == 0) return -1
            ex.selectTrack(0)
            val format = ex.getTrackFormat(0)
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastUs = 0L
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buf, info)
                lastUs = ex.sampleTime
                ex.advance()
            }
            muxer.stop()
            muxer.release()
            muxer = null
            if (!tmp.renameTo(dst)) return -1
            return lastUs / 1000
        } catch (e: Exception) {
            return -1
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}
