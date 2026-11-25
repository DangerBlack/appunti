package org.appunti.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import org.appunti.app.R
import org.appunti.app.data.formatDuration
import org.appunti.app.record.RecorderService

@Composable
fun RecordScreen(vm: AppViewModel) {
    val c = LocalContext.current
    val rec by vm.rec.collectAsState()
    val charging = rememberCharging(c)
    var confirmStop by remember { mutableStateOf(false) }
    KeepScreenOn()

    // Black background: on OLED screens it uses very little power even when left on for hours.
    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    rec.saving -> stringResource(R.string.rec_saving)
                    rec.paused -> stringResource(R.string.rec_paused)
                    else -> stringResource(R.string.rec_recording)
                },
                color = if (rec.paused) Color(0xFFFFB74D) else Red, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(formatDuration(rec.elapsedMs), fontSize = 72.sp, fontWeight = FontWeight.Light, color = Color.White)
            LevelMeter(rec.level)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.rec_hint),
                color = Color.LightGray, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            if (!charging && rec.elapsedMs > 0) {
                Text(
                    stringResource(R.string.rec_charge_tip),
                    color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (rec.saving) {
            CircularProgressIndicator()
        } else {
            Column(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { if (rec.paused) RecorderService.resume(c) else RecorderService.pause(c) },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    if (rec.paused) Text(stringResource(R.string.rec_resume), fontSize = 20.sp)
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        PauseGlyph(); Spacer(Modifier.padding(6.dp)); Text(stringResource(R.string.rec_pause), fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { confirmStop = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                ) { Text(stringResource(R.string.rec_stop), fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.rec_stop_title)) },
            text = { Text(stringResource(R.string.rec_stop_text)) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; RecorderService.stop(c) }) { Text(stringResource(R.string.rec_stop_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.rec_stop_no)) } },
        )
    }
}

@Composable
private fun LevelMeter(level: Float) {
    // Perceptual ("by ear") scale: even a distant voice should move the bar.
    val shown by animateFloatAsState(kotlin.math.sqrt(level).coerceIn(0.02f, 1f), label = "level")
    Box(
        Modifier.fillMaxWidth(0.8f).height(14.dp).padding(top = 4.dp)
            .background(Color(0xFF222222), RoundedCornerShape(7.dp)),
    ) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(Color(0xFF66BB6A), RoundedCornerShape(7.dp)))
    }
}
