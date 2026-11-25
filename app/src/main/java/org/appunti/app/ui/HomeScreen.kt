package org.appunti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.appunti.app.R
import org.appunti.app.asr.ModelState
import org.appunti.app.asr.TrState
import org.appunti.app.data.Lecture
import org.appunti.app.data.Status
import org.appunti.app.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: AppViewModel, onRecord: () -> Unit, onImport: () -> Unit) {
    val lectures by vm.lectures.collectAsState()
    val rec by vm.rec.collectAsState()
    val tr by vm.tr.collectAsState()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_name), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            // Unobtrusive settings: only needed by those who want to connect an AI assistant
            IconButton(onClick = { vm.screen = Screen.Settings }, modifier = Modifier.padding(top = 8.dp, end = 8.dp)) {
                Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = Color.Gray)
            }
        }

        val searching = vm.query.isNotBlank()
        if (rec.active) {
            Surface(
                color = Red, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { vm.screen = Screen.Recording },
            ) {
                Text(
                    stringResource(R.string.home_recording_banner, formatDuration(rec.elapsedMs)),
                    modifier = Modifier.padding(20.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        } else if (!searching) {
            Column(Modifier.fillMaxWidth().padding(vertical = if (lectures.isEmpty()) 64.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onRecord,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(if (lectures.isEmpty()) 180.dp else 140.dp),
                ) {
                    Text(stringResource(R.string.record_button), fontSize = if (lectures.isEmpty()) 24.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                if (lectures.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.home_hint),
                        textAlign = TextAlign.Center, color = Color.LightGray, fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                TextButton(onClick = onImport, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.import_button), fontSize = 16.sp)
                }
            }
        }

        val model by vm.model.collectAsState()
        ModelBanner(model, onMobile = vm::modelOverMobile, onRetry = vm::retryModel)

        vm.busy?.let {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(it, Modifier.padding(start = 12.dp))
            }
        }

        val c = LocalContext.current
        LaunchedEffect(lectures) { vm.refreshSizes(); if (searching) vm.search(vm.query) }

        // Search: in titles and in the transcribed text
        if (lectures.isNotEmpty() || searching) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.search(it) }, singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searching) IconButton(onClick = { vm.search("") }) { Icon(Icons.Default.Clear, stringResource(R.string.search_clear)) }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
            )
        }

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (searching) {
                if (vm.results.isEmpty()) item { Text(stringResource(R.string.search_none), color = Color.Gray) }
                items(vm.results, key = { it.lecture.id }) { hit ->
                    SearchCard(hit, vm.query) { vm.open(hit.lecture.id) }
                }
            } else items(lectures, key = { it.id }) { l ->
                LectureCard(l, tr, vm.sizes[l.id]?.let { it.first + it.second }) { vm.open(l.id) }
            }
            if (!searching && lectures.isNotEmpty() && vm.freeBytes > 0) item {
                Text(
                    stringResource(
                        R.string.size_home,
                        Formatter.formatShortFileSize(c, vm.sizes.values.sumOf { it.first + it.second }),
                        Formatter.formatShortFileSize(c, vm.freeBytes),
                    ),
                    color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchCard(hit: SearchHit, query: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(highlight(hit.lecture.title, query), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hit.count > 0) Text(
                pluralStringResource(R.plurals.search_matches, hit.count, hit.count),
                color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp,
            )
            hit.snippet?.let { Text(highlight(it, query), color = Color.LightGray, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun LectureCard(l: Lecture, tr: TrState, size: Long?, onClick: () -> Unit) {
    val c = LocalContext.current
    val running = tr.running && tr.lectureId == l.id
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(l.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(l.createdAt)) +
                            (if (l.durationMs > 0) " · ${formatDuration(l.durationMs)}" else "") +
                            (if (size != null && size > 0) " · ${Formatter.formatShortFileSize(c, size)}" else ""),
                        color = Color.Gray, fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                // Status as a compact label on the right
                val (label, color) = when {
                    running -> "${(tr.progress * 100).toInt()}%" to MaterialTheme.colorScheme.secondary
                    l.status == Status.RECORDING -> stringResource(R.string.chip_recording) to Red
                    l.status == Status.TRANSCRIBED -> stringResource(R.string.chip_done) to Color(0xFF81C784)
                    l.transcribedMs > 0 -> stringResource(R.string.chip_partial, (l.progress * 100).toInt()) to Color(0xFFFFB74D)
                    else -> stringResource(R.string.chip_todo) to Color(0xFFFFB74D)
                }
                Text(
                    label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (running) LinearProgressIndicator(progress = { tr.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

@Composable
private fun ModelBanner(state: ModelState, onMobile: () -> Unit, onRetry: () -> Unit) {
    if (state is ModelState.Ready || state is ModelState.Checking) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is ModelState.Downloading -> {
                    val pct = (state.done * 100 / state.total.coerceAtLeast(1)).toInt()
                    Text(stringResource(R.string.model_downloading, pct), fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    val c = LocalContext.current
                    Text(
                        stringResource(
                            R.string.model_space,
                            Formatter.formatShortFileSize(c, state.done),
                            Formatter.formatShortFileSize(c, state.total),
                            Formatter.formatShortFileSize(c, state.freeBytes),
                        ),
                        color = Color.LightGray, fontSize = 14.sp,
                    )
                    if (state.waitingForWifi) {
                        Text(stringResource(R.string.model_wait_wifi), color = Color(0xFFFFB74D))
                        Text(stringResource(R.string.model_can_record), color = Color.Gray, fontSize = 14.sp)
                        TextButton(onClick = onMobile) {
                            Text(stringResource(R.string.model_use_data, Formatter.formatShortFileSize(c, state.total - state.done)))
                        }
                    } else if (state.noNetwork) {
                        Text(stringResource(R.string.model_waiting), color = Color(0xFFFFB74D))
                    } else {
                        Text(stringResource(R.string.model_can_record), color = Color.Gray, fontSize = 14.sp)
                    }
                }
                ModelState.Verifying -> Text(stringResource(R.string.model_verifying), fontWeight = FontWeight.SemiBold)
                is ModelState.Failed -> {
                    Text(stringResource(state.messageRes, state.arg ?: ""), color = Color(0xFFFFB74D))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                else -> {}
            }
        }
    }
}
