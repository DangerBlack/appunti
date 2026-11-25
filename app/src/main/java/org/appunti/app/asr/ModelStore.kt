package org.appunti.app.asr

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.text.format.Formatter
import org.appunti.app.BuildConfig
import org.appunti.app.R
import java.io.File
import java.security.MessageDigest

sealed interface ModelState {
    data object Checking : ModelState
    /** freeBytes = free space on the phone right now, to show to the user. */
    /**
     * [waitingForWifi] = paused because the phone is on mobile data (the user may choose to use it);
     * [noNetwork] = paused because there is no connection at all.
     */
    data class Downloading(val done: Long, val total: Long, val waitingForWifi: Boolean, val noNetwork: Boolean, val freeBytes: Long) : ModelState
    data object Verifying : ModelState
    data object Ready : ModelState
    /** [arg] = optional value to insert into the message (e.g. the space required). */
    data class Failed(val messageRes: Int, val arg: String? = null) : ModelState
}

/**
 * The transcription model (~650 MB) is not in the APK: it downloads itself on first launch
 * via Android's DownloadManager (resumes if the network drops, survives the app being closed).
 */
object ModelStore {
    data class ModelFile(val path: String, val size: Long, val sha256: String)

    /** Relative paths, both on the server (BuildConfig.MODEL_BASE_URL) and on the phone. */
    val FILES = listOf(
        ModelFile("parakeet/encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
        ModelFile("parakeet/decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
        ModelFile("parakeet/joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
        ModelFile("parakeet/tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        ModelFile("vad/silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )
    val TOTAL = FILES.sumOf { it.size }

    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    val state: StateFlow<ModelState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private const val PREFS = "models"

    fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "models")
    fun file(c: Context, path: String) = File(dir(c), path)
    private fun okMarker(c: Context) = File(dir(c), "ok-v1")

    fun isReady(c: Context) = okMarker(c).exists()

    fun url(path: String): String = when {
        // Hugging Face and GitHub use file names that differ from our paths
        BuildConfig.MODEL_BASE_URL.isEmpty() && path.startsWith("parakeet/") ->
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/" + path.removePrefix("parakeet/")
        BuildConfig.MODEL_BASE_URL.isEmpty() ->
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        else -> BuildConfig.MODEL_BASE_URL.trimEnd('/') + "/" + path
    }

    /** Call at startup: downloads if needed, otherwise marks as ready. Idempotent. */
    fun ensure(c: Context, allowMobileData: Boolean = false) {
        val ctx = c.applicationContext
        if (isReady(ctx)) { _state.value = ModelState.Ready; return }
        if (started && !allowMobileData) return
        started = true
        job = scope.launch { run(ctx, allowMobileData) }
    }

    private var job: kotlinx.coroutines.Job? = null

    /** The user chose "Download now with mobile data": re-enqueues the missing files without the Wi-Fi restriction. */
    fun useMobileData(c: Context) {
        val ctx = c.applicationContext
        job?.cancel() // the running progress check watches the old downloads, which we are about to remove
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val prefs = ctx.getSharedPreferences(PREFS, 0)
        // Re-enqueue still-pending downloads, allowing metered networks
        for (f in FILES) {
            val id = prefs.getLong(f.path, -1)
            if (id >= 0 && statusOf(dm, id) != DownloadManager.STATUS_SUCCESSFUL) {
                dm.remove(id)
                prefs.edit().remove(f.path).apply()
            }
        }
        prefs.edit().putBoolean("metered", true).apply()
        started = false
        ensure(ctx, allowMobileData = true)
    }

    private suspend fun run(c: Context, allowMobile: Boolean) {
        val dm = c.getSystemService(DownloadManager::class.java)
        val prefs = c.getSharedPreferences(PREFS, 0)
        // By default download only over Wi-Fi; mobile data only if the user asks for it ("metered").
        val metered = allowMobile || prefs.getBoolean("metered", false)
        // Versions 0.1.1-0.6.1 also downloaded over mobile data without asking: downloads still
        // in progress are removed and re-enqueued as Wi-Fi only (once).
        if (!prefs.getBoolean("wifiDefault", false) && !metered) {
            for (f in FILES) {
                val id = prefs.getLong(f.path, -1)
                if (id >= 0 && statusOf(dm, id) != DownloadManager.STATUS_SUCCESSFUL) {
                    dm.remove(id)
                    prefs.edit().remove(f.path).apply()
                }
            }
            prefs.edit().putBoolean("wifiDefault", true).apply()
        }
        dir(c).mkdirs()

        // 0. Free space: we need the whole model plus a margin
        val missing = FILES.sumOf { f -> (f.size - file(c, f.path).length()).coerceAtLeast(0) }
        val free = android.os.StatFs(dir(c).absolutePath).availableBytes
        if (free < missing + 150_000_000L) {
            started = false
            _state.value = ModelState.Failed(R.string.model_no_space, Formatter.formatShortFileSize(c, missing + 150_000_000L))
            return
        }

        // 1. Enqueue the missing files (only once: the ids are kept across launches)
        for (f in FILES) {
            val dest = file(c, f.path)
            if (dest.length() == f.size) continue
            val existing = prefs.getLong(f.path, -1)
            val st = if (existing >= 0) statusOf(dm, existing) else null
            if (st != null && st != DownloadManager.STATUS_FAILED) continue
            if (existing >= 0) dm.remove(existing)
            dest.parentFile?.mkdirs()
            dest.delete()
            val req = DownloadManager.Request(Uri.parse(url(f.path)))
                .setTitle(c.getString(R.string.model_dl_title))
                .setDescription(c.getString(R.string.model_dl_desc))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(dest))
                .setAllowedOverMetered(metered)
                .setAllowedOverRoaming(metered)
            prefs.edit().putLong(f.path, dm.enqueue(req)).apply()
        }

        // 2. Track progress. Free space is measured once, at the start:
        //    it shrinks during the download and showing it "live" would be confusing.
        val freeAtStart = android.os.StatFs(dir(c).absolutePath).availableBytes
        while (true) {
            var done = 0L
            var waitingWifi = false
            var noNet = false
            var failed = false
            var allOk = true
            for (f in FILES) {
                val dest = file(c, f.path)
                val id = prefs.getLong(f.path, -1)
                if (id < 0) { done += dest.length(); continue }
                dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    if (!cur.moveToFirst()) { failed = true; allOk = false; return@use }
                    val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val bytes = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    done += if (status == DownloadManager.STATUS_SUCCESSFUL) f.size else bytes
                    if (status != DownloadManager.STATUS_SUCCESSFUL) allOk = false
                    if (status == DownloadManager.STATUS_FAILED) failed = true
                    if (status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) waitingWifi = true
                    if (status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) noNet = true
                } ?: run { allOk = false }
            }
            if (failed) {
                // Retry the failed files from scratch on the next launch
                for (f in FILES) {
                    val id = prefs.getLong(f.path, -1)
                    if (id >= 0 && statusOf(dm, id) == DownloadManager.STATUS_FAILED) { dm.remove(id); prefs.edit().remove(f.path).apply() }
                }
                started = false
                _state.value = ModelState.Failed(R.string.model_dl_failed)
                return
            }
            // DownloadManager doesn't always say why it's paused: check the active network ourselves
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            val online = cm.activeNetwork != null
            val onData = online && cm.isActiveNetworkMetered
            _state.value = ModelState.Downloading(
                done, TOTAL,
                waitingForWifi = !metered && (onData || waitingWifi),
                noNetwork = !online || (noNet && !waitingWifi && metered),
                freeBytes = freeAtStart,
            )
            if (allOk) break
            delay(1000)
        }

        // 3. Integrity check
        _state.value = ModelState.Verifying
        for (f in FILES) {
            val dest = file(c, f.path)
            if (dest.length() != f.size || sha256(dest) != f.sha256) {
                dest.delete()
                prefs.edit().remove(f.path).apply()
                started = false
                _state.value = ModelState.Failed(R.string.model_dl_corrupt)
                return
            }
        }
        okMarker(c).writeText("ok")
        _state.value = ModelState.Ready
    }

    private fun statusOf(dm: DownloadManager, id: Long): Int? =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
        }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
