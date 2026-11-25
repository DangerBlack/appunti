package org.appunti.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

const val SAMPLE_RATE = 16000

/** A transcribed chunk of speech. start/end are 16 kHz samples from the start of the audio. */
data class Segment(val start: Long, val end: Long, val text: String) {
    val startMs get() = start * 1000 / SAMPLE_RATE
    val endMs get() = end * 1000 / SAMPLE_RATE

    fun toJsonLine(): String = JSONObject().put("s", start).put("e", end).put("t", text).toString()

    companion object {
        fun fromJsonLine(line: String): Segment? = runCatching {
            val o = JSONObject(line)
            Segment(o.getLong("s"), o.getLong("e"), o.getString("t"))
        }.getOrNull()
    }
}

/**
 * Paragraph shown to the user.
 * [key] = start (in samples) of its first chunk: it is stable, so the user's
 * corrections stay attached to the right paragraph.
 */
data class Paragraph(
    val key: Long,
    val startMs: Long,
    val endMs: Long,
    val original: String,
    val segments: List<Segment>,
    val edited: String? = null,
) {
    val text get() = edited ?: original
}

object Transcript {

    fun readSegments(dir: File): List<Segment> {
        val f = File(dir, Lectures.SEGMENTS)
        if (!f.exists()) return emptyList()
        // A truncated last line (crash while writing) is ignored.
        return f.readLines().mapNotNull { if (it.isBlank()) null else Segment.fromJsonLine(it) }
    }

    // ---- User corrections: correzioni.json = { "<key>": "corrected text" } ----

    fun readEdits(dir: File): Map<Long, String> {
        val f = File(dir, Lectures.EDITS)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associate { it.toLong() to o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun writeEdits(dir: File, edits: Map<Long, String>) {
        val o = JSONObject()
        edits.forEach { (k, v) -> o.put(k.toString(), v) }
        val tmp = File(dir, Lectures.EDITS + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, Lectures.EDITS))
    }

    /**
     * Groups chunks into paragraphs: a new paragraph starts after a long pause
     * or when the paragraph exceeds about a minute and a half.
     */
    fun paragraphs(segments: List<Segment>, edits: Map<Long, String> = emptyMap()): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        var group = mutableListOf<Segment>()
        fun close() {
            if (group.isEmpty()) return
            val key = group.first().start
            out += Paragraph(
                key = key,
                startMs = group.first().startMs,
                endMs = group.last().endMs,
                original = group.joinToString(" ") { it.text.trim() },
                segments = group,
                edited = edits[key],
            )
            group = mutableListOf()
        }
        for (s in segments) {
            if (s.text.isBlank()) continue
            val first = group.firstOrNull()
            val last = group.lastOrNull()
            if (first != null && last != null && (s.startMs - last.endMs > 4000 || s.startMs - first.startMs > 90_000)) close()
            group += s
        }
        close()
        return out
    }

    fun toTxt(title: String, paragraphs: List<Paragraph>): String = buildString {
        appendLine(title)
        appendLine()
        for (p in paragraphs) {
            appendLine("[${clock(p.startMs)}] ${p.text}")
            appendLine()
        }
    }

    /** Plain text without timestamps, handy for pasting into a note or document. */
    fun plainText(title: String, paragraphs: List<Paragraph>): String =
        title + "\n\n" + paragraphs.joinToString("\n\n") { it.text }

    /** One subtitle per chunk; a hand-edited paragraph becomes a single subtitle. */
    fun toSrt(paragraphs: List<Paragraph>): String = buildString {
        var i = 1
        fun entry(a: Long, b: Long, t: String) {
            appendLine(i++)
            appendLine("${srtTime(a)} --> ${srtTime(b)}")
            appendLine(t.trim())
            appendLine()
        }
        for (p in paragraphs) {
            if (p.edited != null) entry(p.startMs, p.endMs, p.edited)
            else p.segments.forEach { s -> if (s.text.isNotBlank()) entry(s.startMs, s.endMs, s.text) }
        }
    }

    /**
     * Rewrites trascrizione.txt and .srt from chunks + corrections and,
     * if the transcription is complete, copies them again to Documents.
     */
    fun rebuild(c: Context, l: Lecture, export: Boolean = l.status == Status.TRANSCRIBED) {
        val dir = Lectures.dir(l.id)
        val segs = readSegments(dir)
        if (segs.isEmpty()) return
        val ps = paragraphs(segs, readEdits(dir))
        File(dir, Lectures.TXT).writeText(toTxt(l.title, ps))
        File(dir, Lectures.SRT).writeText(toSrt(ps))
        if (export) Exporter.export(c, l, listOf(Lectures.TXT, Lectures.SRT))
    }

    /** Replaces a whole word (or phrase) in all paragraphs. Returns the number of replacements. */
    fun replaceAll(paragraphs: List<Paragraph>, find: String, replacement: String, edits: MutableMap<Long, String>): Int {
        val f = find.trim()
        if (f.isEmpty()) return 0
        val re = wordRegex(f)
        var count = 0
        for (p in paragraphs) {
            val n = re.findAll(p.text).count()
            if (n == 0) continue
            count += n
            edits[p.key] = re.replace(p.text, Regex.escapeReplacement(replacement.trim()))
        }
        return count
    }

    fun countMatches(paragraphs: List<Paragraph>, find: String): Int {
        val f = find.trim()
        if (f.isEmpty()) return 0
        val re = wordRegex(f)
        return paragraphs.sumOf { re.findAll(it.text).count() }
    }

    // Whole words only: "Arren" must not touch "Warren".
    private fun wordRegex(f: String) = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(f) + "(?![\\p{L}\\p{N}])")

    fun clock(ms: Long): String {
        val t = ms / 1000
        return "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
    }

    private fun srtTime(ms: Long): String =
        "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}

fun formatDuration(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
    else "%d:%02d".format(t / 60, t % 60)
}
