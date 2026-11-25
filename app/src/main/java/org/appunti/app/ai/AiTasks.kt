package org.appunti.app.ai

import org.appunti.app.R
import org.appunti.app.data.Paragraph

/** The documents the assistant can generate. The file name is also the one used in the lecture folder. */
enum class AiKind(val file: String) {
    SUMMARY("riassunto.txt"),
    CLEAN("sistemato.txt"),
}

/**
 * Logic behind the two buttons. A 3-hour lecture is ~27,000 words: we work in chunks
 * (always split between paragraphs) to stay within the limits of any model.
 */
object AiTasks {
    private const val CLEAN_CHUNK_WORDS = 1500      // output is as long as input
    private const val SUMMARY_SINGLE_WORDS = 9000   // below this threshold: a single request
    private const val SUMMARY_CHUNK_WORDS = 6000

    /** @param onStep (current step, total steps) */
    suspend fun run(
        kind: AiKind, cfg: AiConfig, title: String, paragraphs: List<Paragraph>,
        onStep: (Int, Int) -> Unit,
    ): String = when (kind) {
        AiKind.CLEAN -> clean(cfg, paragraphs, onStep)
        AiKind.SUMMARY -> summarize(cfg, title, paragraphs, onStep)
    }

    private suspend fun clean(cfg: AiConfig, ps: List<Paragraph>, onStep: (Int, Int) -> Unit): String {
        val chunks = chunk(ps, CLEAN_CHUNK_WORDS)
        val out = StringBuilder()
        chunks.forEachIndexed { i, text ->
            onStep(i + 1, chunks.size)
            val part = chatRetry(cfg, CLEAN_PROMPT, "Part ${i + 1} of ${chunks.size}:\n\n$text")
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(part.trim())
        }
        return out.toString()
    }

    private suspend fun summarize(cfg: AiConfig, title: String, ps: List<Paragraph>, onStep: (Int, Int) -> Unit): String {
        val words = ps.sumOf { wordCount(it.text) }
        if (words <= SUMMARY_SINGLE_WORDS) {
            onStep(1, 1)
            return chatRetry(cfg, SUMMARY_PROMPT, "Lecture title: $title\n\nTranscript:\n\n${ps.joinToString("\n\n") { it.text }}")
        }
        // Long lecture: notes for each chunk, then a final summary built from the notes
        val chunks = chunk(ps, SUMMARY_CHUNK_WORDS)
        val total = chunks.size + 1
        val notes = chunks.mapIndexed { i, text ->
            onStep(i + 1, total)
            chatRetry(cfg, NOTES_PROMPT, "Part ${i + 1} of ${chunks.size}:\n\n$text")
        }
        onStep(total, total)
        return chatRetry(
            cfg, SUMMARY_PROMPT,
            "Lecture title: $title\n\nThese are study notes taken from each part of the lecture, in order:\n\n" +
                notes.mapIndexed { i, n -> "--- Part ${i + 1} ---\n$n" }.joinToString("\n\n"),
        )
    }

    /** One retry for transient errors (network, timeout, empty response). */
    private suspend fun chatRetry(cfg: AiConfig, system: String, user: String): String =
        try {
            AiClient.chat(cfg, system, user)
        } catch (e: AiException) {
            if (e.messageRes in setOf(R.string.ai_err_network, R.string.ai_err_timeout, R.string.ai_err_empty)) AiClient.chat(cfg, system, user)
            else throw e
        }

    /** Groups paragraphs into chunks of about [maxWords] words, never splitting a paragraph. */
    fun chunk(ps: List<Paragraph>, maxWords: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var n = 0
        for (p in ps) {
            val w = wordCount(p.text)
            if (n > 0 && n + w > maxWords) { out += cur.toString(); cur.clear(); n = 0 }
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(p.text)
            n += w
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }

    // Prompts in English (models follow them better); the reply is always in the lecture's language.
    private const val CLEAN_PROMPT =
        "You receive one part of an automatic speech-to-text transcript of a university lecture. " +
            "Rewrite it as clean, readable text: fix punctuation and capitalization, remove filler words, " +
            "false starts and repetitions, correct obvious transcription mistakes when the meaning is clear, " +
            "and split it into paragraphs. Do NOT summarize, do NOT omit content, do NOT add information, " +
            "headings or comments. Write in the same language as the transcript. Output only the rewritten text."

    private const val NOTES_PROMPT =
        "You receive one part of an automatic speech-to-text transcript of a university lecture. " +
            "Write detailed study notes for this part: main concepts, definitions, examples, formulas and " +
            "references mentioned, as short bullet points. Ignore filler words and transcription errors. " +
            "Write in the same language as the transcript. Output only the notes."

    private const val SUMMARY_PROMPT =
        "You help a university student study a lecture from its automatic transcript (which may contain " +
            "transcription errors). Write a well-structured summary in plain text: first a short overview " +
            "(3-5 sentences), then the main topics in order, each with a '#' heading and '-' bullet points " +
            "covering key ideas, definitions and examples, and finally a list of key terms with a one-line " +
            "explanation each. Do not invent content that is not in the lecture. Write in the same language " +
            "as the lecture. Output only the summary."
}
