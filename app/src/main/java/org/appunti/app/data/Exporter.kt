package org.appunti.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.appunti.app.R
import org.appunti.app.ai.AiKind
import java.io.File
import java.io.InputStream

/**
 * Folder Documents/Appunti/<title>/: holds the audio (the only copy) and a copy of the texts.
 * Files survive uninstalling the app and are visible from any file manager.
 * No permission needed (Android 10+, files created by the app itself).
 */
object Exporter {
    private const val TAG = "Exporter"
    private val collection get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun folderName(l: Lecture): String =
        l.title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().take(80).ifEmpty { l.id }

    private fun relPath(l: Lecture) = "${Environment.DIRECTORY_DOCUMENTS}/Appunti/${folderName(l)}/"

    /** Copies the lecture's text files (transcript, subtitles, AI documents) to Documents. */
    fun export(context: Context, l: Lecture, names: List<String>) {
        val dir = Lectures.dir(l.id)
        for (name in names) {
            if (name.isBlank()) continue
            val src = File(dir, name)
            if (!src.exists()) continue
            runCatching { src.inputStream().use { write(context, l, displayName(context, l, name), it) } }
                .onFailure { Log.w(TAG, "export $name", it) }
        }
    }

    /** Writes the lecture audio directly to Documents. Returns its content:// URI. */
    fun writeAudio(context: Context, l: Lecture, ext: String, input: InputStream): Uri? =
        runCatching { write(context, l, "${folderName(l)}.$ext", input) }
            .onFailure { Log.w(TAG, "audio", it) }.getOrNull()

    /** Deletes the Documents copy of one of the lecture's text files. */
    fun deleteExported(context: Context, l: Lecture, name: String) {
        runCatching {
            context.contentResolver.delete(
                collection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relPath(l), displayName(context, l, name)),
            )
        }.onFailure { Log.w(TAG, "delete $name", it) }
    }

    /**
     * The lecture was renamed: move the audio into the folder with the new name
     * and recreate the text files there, removing the old ones.
     */
    fun rename(context: Context, old: Lecture, new: Lecture, textFiles: List<String>) {
        if (folderName(old) == folderName(new)) {
            export(context, new, textFiles)
            return
        }
        textFiles.forEach { deleteExported(context, old, it) }
        if (new.audioUri.isNotBlank()) runCatching {
            val ext = Audio.extension(context, new)
            context.contentResolver.update(Uri.parse(new.audioUri), ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath(new))
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${folderName(new)}.$ext")
            }, null, null)
        }.onFailure { Log.w(TAG, "move audio", it) }
        export(context, new, textFiles)
    }

    private fun displayName(c: Context, l: Lecture, name: String): String = when (name) {
        AiKind.SUMMARY.file -> "${folderName(l)} - ${c.getString(R.string.ai_file_summary)}.txt"
        AiKind.CLEAN.file -> "${folderName(l)} - ${c.getString(R.string.ai_file_clean)}.txt"
        Lectures.TXT -> "${folderName(l)}.txt"
        Lectures.SRT -> "${folderName(l)}.srt"
        else -> "${folderName(l)}.${name.substringAfterLast('.', "m4a")}"
    }

    fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "txt" -> "text/plain"
        "srt" -> "application/x-subrip"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    /** Creates (or overwrites, if one with the same name exists) a file in Documents/Appunti/<lecture>/. */
    private fun write(context: Context, l: Lecture, display: String, input: InputStream): Uri? {
        val resolver = context.contentResolver
        val rel = relPath(l)
        val existing: Uri? = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(rel, display), null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }

        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(display))
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: return null

        resolver.openOutputStream(uri, "wt")?.use { out -> input.copyTo(out) } ?: return null
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
