package org.appunti.app.asr

import java.io.File
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.appunti.app.data.SAMPLE_RATE
import org.appunti.app.data.Segment

/**
 * Parakeet TDT 0.6B v3 (multilingual, Italian included, with punctuation) via sherpa-onnx,
 * with the Silero VAD splitting the audio at pauses: each speech block (max ~20 s)
 * is transcribed and delivered immediately, so completed work is never lost.
 */
class Transcriber(modelDir: File, numThreads: Int) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "parakeet/encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "parakeet/decoder.int8.onnx").absolutePath,
                    joiner = File(modelDir, "parakeet/joiner.int8.onnx").absolutePath,
                ),
                tokens = File(modelDir, "parakeet/tokens.txt").absolutePath,
                modelType = "nemo_transducer",
                numThreads = numThreads,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    private val vadConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = File(modelDir, "vad/silero_vad.onnx").absolutePath,
            threshold = 0.45f,
            minSilenceDuration = 0.5f,
            minSpeechDuration = 0.25f,
            windowSize = WINDOW,
            maxSpeechDuration = 20f,
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
    )
    private val vad = Vad(assetManager = null, config = vadConfig)

    /**
     * @param source sets the audio file to transcribe
     * @param fromSample resume from here (16 kHz samples) after an interruption
     * @param onSegment called for each transcribed block
     * @param onProgress samples processed so far (from the start of the file)
     */
    fun run(
        source: (android.media.MediaExtractor) -> Unit,
        fromSample: Long,
        isCancelled: () -> Boolean,
        onSegment: (Segment) -> Unit,
        onProgress: (Long) -> Unit,
    ) {
        vad.reset()
        var position = 0L       // samples decoded since the start of the file
        val window = FloatArray(WINDOW)
        var filled = 0
        var lastReport = 0L

        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                recognize(seg, fromSample)?.let(onSegment)
                if (isCancelled()) return
            }
        }

        AudioDecoder.decode(source, isCancelled) { pcm ->
            var i = 0
            // Skip the part already transcribed
            if (position < fromSample) {
                val skip = minOf(pcm.size.toLong(), fromSample - position).toInt()
                position += skip
                i = skip
            }
            while (i < pcm.size) {
                val n = minOf(WINDOW - filled, pcm.size - i)
                System.arraycopy(pcm, i, window, filled, n)
                filled += n
                i += n
                position += n
                if (filled == WINDOW) {
                    vad.acceptWaveform(window.copyOf())
                    filled = 0
                    drain()
                    if (isCancelled()) return@decode
                }
            }
            if (position - lastReport > SAMPLE_RATE) {
                lastReport = position
                onProgress(position)
            }
        }
        if (isCancelled()) return
        if (filled > 0) vad.acceptWaveform(window.copyOf(filled))
        vad.flush()
        drain()
        onProgress(position)
    }

    private fun recognize(seg: SpeechSegment, offset: Long): Segment? {
        val samples = seg.samples
        if (samples.isEmpty()) return null
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            val start = offset + seg.start
            return Segment(start, start + samples.size, text)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        vad.release()
        recognizer.release()
    }

    companion object {
        const val WINDOW = 512
    }
}
