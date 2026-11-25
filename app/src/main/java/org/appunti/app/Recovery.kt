package org.appunti.app

import android.content.Context
import org.appunti.app.data.Audio
import org.appunti.app.data.Lectures
import org.appunti.app.data.Status
import org.appunti.app.record.finalizeRecording
import kotlin.concurrent.thread

/**
 * At process start no recording or transcription can really be in progress:
 * if the store says otherwise, the app was killed abruptly (battery, crash...).
 * Recover the audio recorded so far and mark the transcription as to be resumed.
 */
object Recovery {
    fun run(c: Context) {
        val stale = Lectures.all.value.filter { it.status == Status.RECORDING || it.status == Status.TRANSCRIBING }
        // Up to version 0.4 the audio was also stored inside the app. Now we keep a single copy, in Documents.
        val toMove = Lectures.all.value.filter { it.status != Status.RECORDING && it.audioFile.isNotBlank() }
        if (stale.isEmpty() && toMove.isEmpty()) return
        thread(name = "recovery") {
            for (l in toMove) runCatching { Audio.moveToDocuments(c, l) }
            for (l in stale) {
                when (l.status) {
                    Status.RECORDING -> {
                        finalizeRecording(c, l.id, l.durationMs)
                        Lectures.update(l.id) {
                            it.copy(error = c.getString(R.string.recovery_interrupted))
                        }
                    }
                    Status.TRANSCRIBING -> Lectures.update(l.id) { it.copy(status = Status.RECORDED) }
                    else -> {}
                }
            }
        }
    }
}
