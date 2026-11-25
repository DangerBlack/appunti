package org.appunti.app.record

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.appunti.app.Notifications
import org.appunti.app.R
import org.appunti.app.data.Audio
import org.appunti.app.data.Lectures
import org.appunti.app.data.Status
import org.appunti.app.data.formatDuration
import java.io.File

data class RecState(
    val lectureId: String? = null,
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    /** Microphone level 0..1, to show that it is "hearing". */
    val level: Float = 0f,
    val saving: Boolean = false,
    /** Lecture just finished: the UI opens its page. */
    val finishedId: String? = null,
    val error: String? = null,
)

/**
 * Records in a foreground service (with a persistent notification), so Android doesn't kill it
 * even if the screen turns off or the user switches apps.
 */
class RecorderService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"

        private val _state = MutableStateFlow(RecState())
        val state: StateFlow<RecState> = _state

        fun start(c: Context) = send(c, ACTION_START)
        fun pause(c: Context) = send(c, ACTION_PAUSE)
        fun resume(c: Context) = send(c, ACTION_RESUME)
        fun stop(c: Context) = send(c, ACTION_STOP)
        fun consumeFinished() { _state.value = _state.value.copy(finishedId = null, error = null) }

        private fun send(c: Context, action: String) {
            val i = Intent(c, RecorderService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(c, i) else c.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticker: Job? = null
    private var lectureId: String? = null

    // Stopwatch that accounts for pauses
    private var accumulatedMs = 0L
    private var runningSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            else -> if (recorder == null) stopSelf() // system restart without state: nothing to do
        }
        return START_NOT_STICKY
    }

    private fun elapsed(): Long =
        accumulatedMs + if (runningSince > 0) SystemClock.elapsedRealtime() - runningSince else 0

    private fun notification(text: String) =
        Notifications.builder(this, Notifications.CH_RECORD, getString(R.string.rec_notif_title), text, lectureId)
            .setOngoing(true)
            .build()

    private fun startRecording() {
        if (recorder != null) return
        ServiceCompat.startForeground(
            this, Notifications.ID_RECORD, notification(getString(R.string.rec_notif_tap)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        val lecture = Lectures.create()
        lectureId = lecture.id
        val out = File(Lectures.dir(lecture.id), Lectures.RECORDING_RAW)
        try {
            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(32000)
                setAudioEncodingBitRate(64000) // ~29 MB per hour
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            Lectures.delete(lecture.id)
            _state.value = RecState(error = getString(R.string.rec_mic_busy))
            stopSelf()
            return
        }
        Lectures.save(lecture.copy(audioFile = Lectures.RECORDING_RAW))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "appunti:rec").apply { acquire(6 * 3600 * 1000L) }
        accumulatedMs = 0
        runningSince = SystemClock.elapsedRealtime()
        _state.value = RecState(lectureId = lecture.id, active = true)
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var lastSave = 0L
            while (isActive) {
                val r = recorder ?: break
                val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                val e = elapsed()
                _state.value = _state.value.copy(elapsedMs = e, level = (amp / 32767f).coerceIn(0f, 1f))
                // Save the duration every 10 s: if the phone shuts down we know how much was recorded.
                if (e - lastSave > 10_000) {
                    lastSave = e
                    lectureId?.let { id -> Lectures.update(id) { it.copy(durationMs = e) } }
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.ID_RECORD, notification(getString(R.string.rec_notif_duration, formatDuration(e))))
                }
                delay(150)
            }
        }
    }

    private fun pauseRecording() {
        val r = recorder ?: return
        if (_state.value.paused) return
        runCatching { r.pause() }
        accumulatedMs = elapsed()
        runningSince = 0
        _state.value = _state.value.copy(paused = true, level = 0f, elapsedMs = accumulatedMs)
    }

    private fun resumeRecording() {
        val r = recorder ?: return
        if (!_state.value.paused) return
        runCatching { r.resume() }
        runningSince = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(paused = false)
    }

    private fun stopRecording() {
        val r = recorder ?: run { stopSelf(); return }
        val id = lectureId ?: return
        val dur = elapsed()
        ticker?.cancel()
        recorder = null
        _state.value = _state.value.copy(saving = true, elapsedMs = dur, level = 0f)
        scope.launch {
            runCatching { r.stop() }
            r.release()
            finalizeRecording(this@RecorderService, id, dur)
            wakeLock?.takeIf { it.isHeld }?.release()
            _state.value = RecState(finishedId = id)
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // If the system kills us anyway, close the file cleanly.
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
            lectureId?.let { finalizeRecording(this, it, elapsed()) }
            _state.value = RecState(finishedId = lectureId)
        }
        recorder = null
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }
}

/** Converts the raw recording to M4A and moves it to Documents (the only copy of the audio). */
fun finalizeRecording(c: Context, id: String, fallbackDurationMs: Long) {
    val dir = Lectures.dir(id)
    val raw = File(dir, Lectures.RECORDING_RAW)
    val m4a = File(dir, Lectures.AUDIO_M4A)
    var audio = Lectures.RECORDING_RAW
    var dur = fallbackDurationMs
    if (raw.exists() && raw.length() > 0) {
        val d = Remux.adtsToM4a(raw, m4a)
        if (d >= 0) {
            raw.delete()
            audio = Lectures.AUDIO_M4A
            if (d > 0) dur = d
        }
    } else if (m4a.exists()) {
        audio = Lectures.AUDIO_M4A
    }
    val l = Lectures.update(id) { it.copy(audioFile = audio, durationMs = dur, status = Status.RECORDED) } ?: return
    Audio.moveToDocuments(c, l)
}
