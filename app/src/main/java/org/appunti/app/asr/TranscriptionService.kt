package org.appunti.app.asr

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.appunti.app.Notifications
import org.appunti.app.R
import org.appunti.app.data.Audio
import org.appunti.app.data.Exporter
import org.appunti.app.data.Lectures
import org.appunti.app.data.SAMPLE_RATE
import org.appunti.app.data.Status
import org.appunti.app.data.Transcript
import java.io.File
import java.io.FileOutputStream

data class TrState(
    val lectureId: String? = null,
    val running: Boolean = false,
    val loadingModel: Boolean = false,
    val progress: Float = 0f,
    val remainingSec: Long? = null,
    /** Number of transcribed blocks: the UI uses it to reload the partial text. */
    val segments: Int = 0,
    val hot: Boolean = false,
)

/**
 * Transcribes in a foreground service. Each transcribed block is written immediately to
 * segments.jsonl: if the app is closed, transcription resumes where it left off.
 */
class TranscriptionService : Service() {

    companion object {
        private const val TAG = "Transcription"
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"

        private val _state = MutableStateFlow(TrState())
        val state: StateFlow<TrState> = _state

        fun start(c: Context, lectureId: String) = ContextCompat.startForegroundService(
            c, Intent(c, TranscriptionService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, lectureId),
        )

        fun cancel(c: Context) {
            c.startService(Intent(c, TranscriptionService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id == null || _state.value.running) return START_NOT_STICKY
                ServiceCompat.startForeground(
                    this, Notifications.ID_TRANSCRIBE,
                    notification(id, getString(R.string.tr_preparing), 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                cancelled = false
                _state.value = TrState(lectureId = id, running = true, loadingModel = true)
                scope.launch { work(id) }
            }
            ACTION_CANCEL -> cancelled = true
            else -> if (!_state.value.running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15: dataSync services are limited to 6 hours per day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
    }

    private fun notification(id: String, text: String, percent: Int) =
        Notifications.builder(this, Notifications.CH_TRANSCRIBE, getString(R.string.ch_transcribing), text, id)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()

    private fun work(id: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "appunti:asr").apply { acquire(8 * 3600 * 1000L) }

        val lecture = Lectures.get(id)
        val dir = Lectures.dir(id)
        if (!ModelStore.isReady(this)) {
            finish(id, ok = false, error = getString(R.string.tr_model_wait_err))
            return
        }
        if (lecture == null || !Audio.has(lecture)) {
            finish(id, ok = false, error = getString(R.string.tr_no_audio))
            return
        }
        val done = Transcript.readSegments(dir)
        val from = done.lastOrNull()?.end ?: 0L
        // Rewrite the file without any lines corrupted by an interruption
        File(dir, Lectures.SEGMENTS).writeText(done.joinToString("") { it.toJsonLine() + "\n" })
        val totalSamples = (lecture.durationMs * SAMPLE_RATE / 1000).coerceAtLeast(1)
        Lectures.update(id) { it.copy(status = Status.TRANSCRIBING, error = null, transcribedMs = from * 1000 / SAMPLE_RATE) }

        var count = done.size
        val threads = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else (it / 2).coerceAtLeast(1) }

        try {
            Transcriber(ModelStore.dir(this), threads).use { tr ->
                _state.value = _state.value.copy(loadingModel = false, progress = from.toFloat() / totalSamples, segments = count)
                val t0 = SystemClock.elapsedRealtime()
                var lastNotify = 0L
                var lastSaved = 0L
                FileOutputStream(File(dir, Lectures.SEGMENTS), true).bufferedWriter().use { out ->
                    tr.run(
                        source = { ex -> Audio.setSource(this@TranscriptionService, lecture, ex) },
                        fromSample = from,
                        isCancelled = { cancelled },
                        onSegment = { seg ->
                            out.write(seg.toJsonLine())
                            out.write("\n")
                            out.flush()
                            count++
                            _state.value = _state.value.copy(segments = count)
                            // Phone too hot: slow down so it doesn't throttle or freeze.
                            val hot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                            if (hot) Thread.sleep(3000)
                            if (hot != _state.value.hot) _state.value = _state.value.copy(hot = hot)
                        },
                        onProgress = { pos ->
                            val p = (pos.toFloat() / totalSamples).coerceIn(0f, 1f)
                            val elapsed = SystemClock.elapsedRealtime() - t0
                            val doneNow = pos - from
                            val remaining = if (doneNow > SAMPLE_RATE * 30 && elapsed > 5000)
                                ((totalSamples - pos).coerceAtLeast(0) * elapsed / doneNow / 1000) else null
                            _state.value = _state.value.copy(progress = p, remainingSec = remaining)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastNotify > 3000) {
                                lastNotify = now
                                val txt = remaining?.let { getString(R.string.tr_notif_remaining, (p * 100).toInt(), minutes(this@TranscriptionService, it)) } ?: "${(p * 100).toInt()}%"
                                nm.notify(Notifications.ID_TRANSCRIBE, notification(id, txt, (p * 100).toInt().coerceAtLeast(1)))
                            }
                            if (now - lastSaved > 15_000) {
                                lastSaved = now
                                Lectures.update(id) { it.copy(transcribedMs = pos * 1000 / SAMPLE_RATE) }
                            }
                        },
                    )
                }
            }
            if (cancelled) finish(id, ok = false, error = null) else finish(id, ok = true, error = null)
        } catch (e: Throwable) {
            Log.e(TAG, "transcription failed", e)
            val msg = getString(if (e is OutOfMemoryError) R.string.tr_oom else R.string.tr_error)
            finish(id, ok = false, error = msg)
        }
    }

    private fun finish(id: String, ok: Boolean, error: String?) {
        val dir = Lectures.dir(id)
        val segs = Transcript.readSegments(dir)
        val lecture = Lectures.update(id) {
            it.copy(
                status = if (ok) Status.TRANSCRIBED else Status.RECORDED,
                transcribedMs = if (ok) it.durationMs else (segs.lastOrNull()?.endMs ?: 0),
                error = error,
            )
        }
        if (lecture != null && segs.isNotEmpty()) Transcript.rebuild(this, lecture, export = ok)
        if (ok && lecture != null) {
            getSystemService(NotificationManager::class.java).notify(
                Notifications.ID_DONE,
                Notifications.builder(this, Notifications.CH_DONE, getString(R.string.tr_done), lecture.title, id)
                    .setAutoCancel(true).build(),
            )
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        _state.value = TrState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelled = true
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }
}

fun minutes(c: Context, sec: Long): String = when {
    sec < 3600 -> ((sec + 30) / 60).toInt().coerceAtLeast(1).let { c.resources.getQuantityString(R.plurals.minutes, it, it) }
    else -> c.getString(R.string.hours_minutes, (sec / 3600).toInt(), ((sec % 3600 + 30) / 60).toInt())
}
