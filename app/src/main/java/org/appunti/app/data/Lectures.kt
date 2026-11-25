package org.appunti.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.appunti.app.R
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Status { RECORDING, RECORDED, TRANSCRIBING, TRANSCRIBED }

data class Lecture(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    /** Internal audio file: only exists while recording (or for old lectures still to be moved). */
    val audioFile: String = "",
    /** The actual audio, in Documents/Appunti (content://…). The only copy. */
    val audioUri: String = "",
    val status: Status = Status.RECORDING,
    /** How far transcription has got (to resume after an interruption). */
    val transcribedMs: Long = 0,
    /** Last error to show the user, in plain words. */
    val error: String? = null,
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (transcribedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("createdAt", createdAt)
        .put("durationMs", durationMs).put("audioFile", audioFile).put("audioUri", audioUri)
        .put("status", status.name).put("transcribedMs", transcribedMs)
        .put("error", error ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Lecture(
            id = o.getString("id"),
            title = o.optString("title", ""),
            createdAt = o.optLong("createdAt"),
            durationMs = o.optLong("durationMs"),
            audioFile = o.optString("audioFile", ""),
            audioUri = o.optString("audioUri", ""),
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.RECORDED),
            transcribedMs = o.optLong("transcribedMs"),
            error = if (o.isNull("error")) null else o.optString("error"),
        )
    }
}

/**
 * Lecture store: one folder per lecture in filesDir/lezioni/<id>/ with
 * meta.json, the audio, segments.jsonl (chunked transcript) and the final files.
 */
object Lectures {
    const val META = "meta.json"
    const val SEGMENTS = "segments.jsonl"
    const val TXT = "trascrizione.txt"
    const val SRT = "trascrizione.srt"
    const val EDITS = "correzioni.json"
    const val RECORDING_RAW = "registrazione.aac"
    const val AUDIO_M4A = "audio.m4a"

    private lateinit var root: File
    private lateinit var app: Context
    private val _all = MutableStateFlow<List<Lecture>>(emptyList())
    val all: StateFlow<List<Lecture>> = _all

    fun init(context: Context) {
        app = context.applicationContext
        root = File(context.filesDir, "lezioni").apply { mkdirs() }
        refresh()
    }

    fun dir(id: String) = File(root, id)

    @Synchronized
    fun refresh() {
        _all.value = root.listFiles().orEmpty()
            .mapNotNull { d -> runCatching { Lecture.fromJson(JSONObject(File(d, META).readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    fun get(id: String): Lecture? = _all.value.firstOrNull { it.id == id }
        ?: runCatching { Lecture.fromJson(JSONObject(File(dir(id), META).readText())) }.getOrNull()

    @Synchronized
    fun save(l: Lecture) {
        val d = dir(l.id).apply { mkdirs() }
        val tmp = File(d, "$META.tmp")
        tmp.writeText(l.toJson().toString())
        tmp.renameTo(File(d, META))
        refresh()
    }

    @Synchronized
    fun update(id: String, f: (Lecture) -> Lecture): Lecture? {
        val l = get(id) ?: return null
        val n = f(l)
        save(n)
        return n
    }

    fun create(title: String? = null): Lecture {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val l = Lecture(id = id, title = title ?: defaultTitle(now), createdAt = now)
        save(l)
        return l
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        refresh()
    }

    fun defaultTitle(time: Long): String =
        app.getString(R.string.default_title, SimpleDateFormat("d MMMM, HH:mm", Locale.getDefault()).format(Date(time)))
}
