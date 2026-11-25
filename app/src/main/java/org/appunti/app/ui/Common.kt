package org.appunti.app.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

val Red = Color(0xFFE53935)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Red,
            onPrimary = Color.White,
            secondary = Color(0xFF90CAF9),
            background = Color(0xFF121212),
            surface = Color(0xFF121212),
            surfaceVariant = Color(0xFF1E1E1E),
        ),
    ) {
        // Surface sets the default text color (white) for the whole app
        androidx.compose.material3.Surface(color = Color(0xFF121212), contentColor = Color.White, content = content)
    }
}

/** Keeps the screen on while this part of the UI is visible. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

fun isCharging(c: Context): Boolean {
    val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val s = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
}

@Composable
fun rememberCharging(c: Context): Boolean {
    var charging by remember { mutableStateOf(isCharging(c)) }
    LaunchedEffect(Unit) {
        while (true) { charging = isCharging(c); delay(5000) }
    }
    return charging
}

/** Hand-drawn pause icon (the core icon set doesn't include one). */
@Composable
fun PauseGlyph(color: Color = Color.White) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 6.dp, height = 20.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Box(Modifier.size(width = 6.dp, height = 20.dp).background(color, RoundedCornerShape(2.dp)))
    }
}

class Player(setup: (MediaPlayer) -> Unit) {
    private val mp: MediaPlayer? = runCatching { MediaPlayer().apply { setup(this); prepare() } }.getOrNull()
    var playing by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    val duration: Long = mp?.duration?.toLong() ?: 0L
    val ok get() = mp != null

    init { mp?.setOnCompletionListener { playing = false } }

    fun toggle() {
        val m = mp ?: return
        if (m.isPlaying) { m.pause(); playing = false } else { m.start(); playing = true }
    }

    fun seekTo(ms: Long, play: Boolean = false) {
        val m = mp ?: return
        m.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        position = ms
        if (play && !m.isPlaying) { m.start(); playing = true }
    }

    fun poll() { mp?.let { if (it.isPlaying) position = it.currentPosition.toLong() } }
    fun release() { mp?.release() }
}

@Composable
fun rememberPlayer(key: String, setup: (MediaPlayer) -> Unit): Player {
    val p = remember(key) { Player(setup) }
    DisposableEffect(p) { onDispose { p.release() } }
    LaunchedEffect(p, p.playing) {
        while (p.playing) { p.poll(); delay(250) }
    }
    return p
}

/** Text with occurrences of [query] highlighted (case- and accent-insensitive). */
fun highlight(text: String, query: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        for (r in org.appunti.app.data.Search.ranges(text, query)) {
            addStyle(
                androidx.compose.ui.text.SpanStyle(background = Color(0xFF8D6E00), color = Color.White),
                r.first, r.last + 1,
            )
        }
    }
