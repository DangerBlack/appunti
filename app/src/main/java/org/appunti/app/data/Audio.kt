package org.appunti.app.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Where a lecture's audio lives. Normally a single file in Documents (audioUri).
 * The internal file (audioFile) only exists while recording, or for old lectures
 * not yet moved.
 */
object Audio {
    fun has(l: Lecture) = l.audioUri.isNotBlank() || l.audioFile.isNotBlank()

    private fun internal(l: Lecture) = l.audioFile.takeIf { it.isNotBlank() }?.let { File(Lectures.dir(l.id), it) }

    fun setSource(c: Context, l: Lecture, ex: MediaExtractor) {
        if (l.audioUri.isNotBlank()) ex.setDataSource(c, Uri.parse(l.audioUri), null)
        else ex.setDataSource(internal(l)!!.absolutePath)
    }

    fun setSource(c: Context, l: Lecture, mp: MediaPlayer) {
        if (l.audioUri.isNotBlank()) mp.setDataSource(c, Uri.parse(l.audioUri))
        else mp.setDataSource(internal(l)!!.absolutePath)
    }

    /** URI to hand to the "Share" sheet. */
    fun shareUri(c: Context, l: Lecture): Uri? = when {
        l.audioUri.isNotBlank() -> Uri.parse(l.audioUri)
        else -> internal(l)?.takeIf { it.exists() }?.let {
            androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.files", it)
        }
    }

    fun size(c: Context, l: Lecture): Long = when {
        l.audioUri.isNotBlank() -> runCatching {
            c.contentResolver.query(Uri.parse(l.audioUri), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        else -> internal(l)?.length() ?: 0L
    }

    fun extension(c: Context, l: Lecture): String = when {
        l.audioUri.isNotBlank() -> runCatching {
            c.contentResolver.query(Uri.parse(l.audioUri), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0).substringAfterLast('.', "") else "" }
        }.getOrNull()?.ifBlank { null }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(c.contentResolver.getType(Uri.parse(l.audioUri))) ?: "m4a"
        else -> l.audioFile.substringAfterLast('.', "m4a")
    }

    /** Deletes the audio, wherever it is. */
    fun delete(c: Context, l: Lecture) {
        if (l.audioUri.isNotBlank()) runCatching { c.contentResolver.delete(Uri.parse(l.audioUri), null, null) }
        internal(l)?.delete()
    }

    /**
     * Moves internal audio to Documents, keeping a single copy.
     * Returns the updated lecture (unchanged if the move fails; nothing is deleted in that case).
     */
    fun moveToDocuments(c: Context, l: Lecture): Lecture {
        val f = internal(l) ?: return l
        if (!f.exists()) return l
        val uri = f.inputStream().use { Exporter.writeAudio(c, l, f.extension.ifBlank { "m4a" }, it) } ?: return l
        f.delete()
        return Lectures.update(l.id) { it.copy(audioUri = uri.toString(), audioFile = "") } ?: l
    }
}
