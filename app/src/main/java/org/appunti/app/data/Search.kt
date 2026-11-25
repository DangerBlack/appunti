package org.appunti.app.data

import java.text.Normalizer

/** Simple search, ignoring case and accents ("perche" matches "perché"). */
object Search {
    private val marks = Regex("\\p{M}+")

    /** Lowercase, accents stripped. For Latin letters the length stays the same as the original. */
    fun norm(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    /** Ranges (start, end) of occurrences of [query] in [text], ready for highlighting. */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = norm(query.trim())
        if (q.isEmpty()) return emptyList()
        val n = norm(text)
        if (n.length != text.length) return emptyList() // exotic characters: no highlighting rather than wrong highlighting
        val out = mutableListOf<IntRange>()
        var i = n.indexOf(q)
        while (i >= 0) {
            out += i until i + q.length
            i = n.indexOf(q, i + q.length)
        }
        return out
    }

    fun count(normText: String, query: String): Int {
        val q = norm(query.trim())
        if (q.isEmpty()) return 0
        var c = 0
        var i = normText.indexOf(q)
        while (i >= 0) { c++; i = normText.indexOf(q, i + q.length) }
        return c
    }

    /** A short piece of text around the first occurrence, for the results list. */
    fun snippet(text: String, normText: String, query: String, radius: Int = 70): String? {
        val q = norm(query.trim())
        val i = normText.indexOf(q)
        if (q.isEmpty() || i < 0) return null
        val src = if (normText.length == text.length) text else normText
        var a = (i - radius).coerceAtLeast(0)
        var b = (i + q.length + radius).coerceAtMost(src.length)
        // Cut at a space so words aren't split
        if (a > 0) src.indexOf(' ', a).takeIf { it in a until i }?.let { a = it + 1 }
        if (b < src.length) src.lastIndexOf(' ', b).takeIf { it > i + q.length }?.let { b = it }
        return (if (a > 0) "…" else "") + src.substring(a, b).replace('\n', ' ') + (if (b < src.length) "…" else "")
    }
}
