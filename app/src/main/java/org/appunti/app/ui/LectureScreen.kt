package org.appunti.app.ui

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.appunti.app.R
import org.appunti.app.ai.AiKind
import org.appunti.app.asr.ModelState
import org.appunti.app.asr.TrState
import org.appunti.app.asr.minutes
import org.appunti.app.data.Audio
import org.appunti.app.data.Exporter
import org.appunti.app.data.Lecture
import org.appunti.app.data.Lectures
import org.appunti.app.data.Paragraph
import org.appunti.app.data.Search
import androidx.compose.foundation.lazy.rememberLazyListState
import org.appunti.app.data.Status
import org.appunti.app.data.Transcript
import org.appunti.app.data.formatDuration
import java.io.File

@Composable
fun LectureScreen(vm: AppViewModel, id: String) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val lectures by vm.lectures.collectAsState()
    val tr by vm.tr.collectAsState()
    val lecture = lectures.firstOrNull { it.id == id }
    if (lecture == null) {
        LaunchedEffect(Unit) { vm.home() }
        return
    }
    val running = tr.running && tr.lectureId == id
    val done = lecture.status == Status.TRANSCRIBED && !running
    val hasAudio = Audio.has(lecture)

    var paragraphs by remember(id) { mutableStateOf<List<Paragraph>>(emptyList()) }
    LaunchedEffect(id, tr.segments, lecture.status, vm.editsVersion) { paragraphs = vm.paragraphs(id) }
    LaunchedEffect(lecture, vm.aiVersion, vm.editsVersion) { vm.refreshSizes() }

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deletingAudio by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Paragraph?>(null) }
    var regenerate by remember { mutableStateOf<AiKind?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = { vm.home() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(
                lecture.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable { renaming = true },
            )
            IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, stringResource(R.string.rename)) }
        }

        // Create the player only if the audio still exists
        val player = if (hasAudio) rememberPlayer(lecture.audioUri + lecture.audioFile) { Audio.setSource(c, lecture, it) } else null

        // Coming from a search: open at the first paragraph containing the word
        val listState = rememberLazyListState()
        val query = vm.query
        val firstParagraphItem = 1 + (if (lecture.error != null) 1 else 0) + (if (hasAudio) 1 else 0) +
            (if (done && paragraphs.isNotEmpty()) 2 else if (paragraphs.isNotEmpty() && hasAudio) 1 else 0) +
            (if (done && paragraphs.isNotEmpty() && vm.aiConfig.isConfigured) 1 else 0)
        var scrolledFor by remember(id) { mutableStateOf<String?>(null) }
        LaunchedEffect(paragraphs, query) {
            if (query.isBlank() || paragraphs.isEmpty() || scrolledFor == query) return@LaunchedEffect
            val i = paragraphs.indexOfFirst { Search.ranges(it.text, query).isNotEmpty() }
            if (i >= 0) listState.scrollToItem(firstParagraphItem + i)
            scrolledFor = query
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                if (player != null) PlayerRow(player)
                else Text(stringResource(R.string.audio_deleted), color = Color.Gray)
            }

            lecture.error?.let { err ->
                item {
                    Surface(color = Color(0xFF4E342E), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(err, Modifier.weight(1f))
                            TextButton(onClick = { vm.clearError(id) }) { Text(stringResource(android.R.string.ok)) }
                        }
                    }
                }
            }

            if (hasAudio) item { TranscriptionBox(vm, lecture, tr, running) }

            if (done && paragraphs.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                clipboard.setText(AnnotatedString(Transcript.plainText(lecture.title, paragraphs)))
                                Toast.makeText(c, c.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.copy_all)) }
                            Button(onClick = { shareText(c, lecture, paragraphs) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.send_text))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { replacing = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.fix_word)) }
                            if (hasAudio) OutlinedButton(onClick = { shareAudio(c, lecture) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.send_audio))
                            }
                        }
                    }
                }
                item {
                    Text(
                        (if (hasAudio) stringResource(R.string.hint_seek) else "") + stringResource(R.string.hint_edit),
                        color = Color.Gray, fontSize = 13.sp,
                    )
                }
            } else if (!done && paragraphs.isNotEmpty() && hasAudio) {
                item {
                    OutlinedButton(onClick = { shareAudio(c, lecture) }) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.send_audio))
                    }
                }
            }

            if (done && paragraphs.isNotEmpty() && vm.aiConfig.isConfigured) {
                item { AiBox(vm, id, onRegenerate = { regenerate = it }) }
            }

            items(paragraphs, key = { it.key }) { p ->
                ParagraphView(
                    p, query,
                    onSeek = player?.let { pl -> { pl.seekTo(p.startMs, play = true) } },
                    onEdit = if (done) ({ editing = p }) else null,
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.copy_location, Exporter.folderName(lecture)),
                    color = Color.Gray, fontSize = 13.sp,
                )
                vm.sizes[id]?.let { (audio, texts) ->
                    Text(
                        stringResource(
                            R.string.size_lecture,
                            Formatter.formatShortFileSize(c, audio + texts),
                            Formatter.formatShortFileSize(c, audio),
                            Formatter.formatShortFileSize(c, texts),
                        ),
                        color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (done && hasAudio) {
                    TextButton(onClick = { deletingAudio = true }) {
                        Text(stringResource(R.string.delete_audio_button), color = Color(0xFFEF9A9A))
                    }
                }
                TextButton(onClick = { deleting = true }, enabled = !running) {
                    Text(stringResource(R.string.delete_lecture_button), color = Color(0xFFEF9A9A))
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(lecture.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.lecture_name)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(id, text); renaming = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_lecture_title)) },
            text = { Text(stringResource(R.string.delete_lecture_text)) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete(id) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deletingAudio) {
        AlertDialog(
            onDismissRequest = { deletingAudio = false },
            title = { Text(stringResource(R.string.delete_audio_title)) },
            text = {
                Text(stringResource(R.string.delete_audio_text))
            },
            confirmButton = {
                TextButton(onClick = { deletingAudio = false; vm.deleteAudio(id) }) { Text(stringResource(R.string.delete_audio_confirm), color = Color(0xFFEF9A9A)) }
            },
            dismissButton = { TextButton(onClick = { deletingAudio = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    editing?.let { p ->
        var text by remember(p.key) { mutableStateOf(p.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.edit_title, Transcript.clock(p.startMs))) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, lineHeight = 24.sp),
                    )
                    if (p.edited != null) {
                        TextButton(onClick = { text = p.original }) { Text(stringResource(R.string.restore_original)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.editParagraph(id, p, text); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    regenerate?.let { kind ->
        AlertDialog(
            onDismissRequest = { regenerate = null },
            title = { Text(stringResource(R.string.ai_regenerate_title)) },
            text = { Text(stringResource(R.string.ai_regenerate_text)) },
            confirmButton = { TextButton(onClick = { regenerate = null; vm.runAi(id, kind) }) { Text(stringResource(R.string.ai_regenerate_yes)) } },
            dismissButton = { TextButton(onClick = { regenerate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (replacing) {
        var find by remember { mutableStateOf("") }
        var repl by remember { mutableStateOf("") }
        val found = remember(find, paragraphs) { Transcript.countMatches(paragraphs, find) }
        AlertDialog(
            onDismissRequest = { replacing = false },
            title = { Text(stringResource(R.string.replace_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = find, onValueChange = { find = it }, singleLine = true, label = { Text(stringResource(R.string.replace_wrong)) })
                    OutlinedTextField(value = repl, onValueChange = { repl = it }, singleLine = true, label = { Text(stringResource(R.string.replace_right)) })
                    if (find.isNotBlank()) Text(
                        if (found == 0) stringResource(R.string.replace_none) else pluralStringResource(R.plurals.replace_found, found, found),
                        color = if (found == 0) Color(0xFFFFB74D) else Color.Gray, fontSize = 14.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = found > 0 && repl.isNotBlank(), onClick = {
                    replacing = false
                    scope.launch {
                        val n = vm.replaceAll(id, find, repl)
                        Toast.makeText(c, c.resources.getQuantityString(R.plurals.replace_done, n, n), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.replace_all)) }
            },
            dismissButton = { TextButton(onClick = { replacing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PlayerRow(player: Player) {
    if (!player.ok) {
        Text(stringResource(R.string.audio_open_failed), color = Color.Gray)
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { player.toggle() }, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                if (player.playing) PauseGlyph() else Icon(Icons.Default.PlayArrow, stringResource(R.string.listen), Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Slider(
                    value = player.position.toFloat(),
                    onValueChange = { player.seekTo(it.toLong()) },
                    valueRange = 0f..player.duration.coerceAtLeast(1).toFloat(),
                )
                Text("${formatDuration(player.position)} / ${formatDuration(player.duration)}", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun TranscriptionBox(vm: AppViewModel, l: Lecture, tr: TrState, running: Boolean) {
    val c = LocalContext.current
    if (l.status == Status.TRANSCRIBED && !running) return
    val charging = rememberCharging(c)

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (running) {
                KeepScreenOn()
                Text(
                    if (tr.loadingModel) stringResource(R.string.tr_preparing) else stringResource(R.string.tr_progress, (tr.progress * 100).toInt()),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                if (tr.loadingModel) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { tr.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                tr.remainingSec?.let { Text(stringResource(R.string.tr_remaining, minutes(c, it)), fontSize = 16.sp) }
                Text(stringResource(R.string.tr_keep_open), color = Color.LightGray, fontSize = 15.sp)
                if (!charging) Text(stringResource(R.string.tr_charge), color = Color(0xFFFFB74D), fontSize = 15.sp)
                if (tr.hot) Text(stringResource(R.string.tr_hot), color = Color(0xFFFFB74D), fontSize = 15.sp)
                TextButton(onClick = { vm.cancelTranscription() }) { Text(stringResource(R.string.tr_stop)) }
            } else {
                val model by vm.model.collectAsState()
                val modelReady = model is ModelState.Ready
                val resume = l.transcribedMs > 0
                Text(
                    if (resume) stringResource(R.string.tr_resume_title, (l.progress * 100).toInt()) else stringResource(R.string.tr_title),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.tr_info),
                    color = Color.LightGray, fontSize = 15.sp,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.transcribe(l.id) },
                    enabled = !tr.running && modelReady,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) { Text(stringResource(if (resume) R.string.tr_resume_btn else R.string.tr_btn), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                if (!modelReady) Text(
                    stringResource(R.string.tr_model_not_ready),
                    color = Color(0xFFFFB74D), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                )
                if (tr.running) Text(stringResource(R.string.tr_busy), color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ParagraphView(p: Paragraph, query: String, onSeek: (() -> Unit)?, onEdit: (() -> Unit)?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Transcript.clock(p.startMs), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp,
                modifier = (if (onSeek != null) Modifier.clickable(onClick = onSeek) else Modifier).padding(vertical = 2.dp),
            )
            if (p.edited != null) Text(stringResource(R.string.edited_tag), color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (onEdit != null) IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, stringResource(R.string.edit), Modifier.size(18.dp), tint = Color.Gray)
            }
        }
        SelectionContainer { Text(highlight(p.text, query), fontSize = 17.sp, lineHeight = 25.sp) }
    }
}

private fun share(c: Context, l: Lecture, name: String, mime: String) {
    if (name.isBlank()) return
    val f = File(Lectures.dir(l.id), name)
    if (!f.exists()) return
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, l.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(i, c.getString(R.string.share)))
}

private fun shareAudio(c: Context, l: Lecture) {
    val uri = Audio.shareUri(c, l) ?: return
    val i = Intent(Intent.ACTION_SEND).setType("audio/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, l.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(i, c.getString(R.string.share)))
}

/** Shares the text itself (pastes into WhatsApp, Keep, email...) plus the .txt file (for Drive). */
private fun shareText(c: Context, l: Lecture, paragraphs: List<Paragraph>) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, l.title)
        .putExtra(Intent.EXTRA_TEXT, Transcript.plainText(l.title, paragraphs))
    val f = File(Lectures.dir(l.id), Lectures.TXT)
    if (f.exists()) {
        i.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(c, "${c.packageName}.files", f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    c.startActivity(Intent.createChooser(i, c.getString(R.string.share_text_title)))
}

/** AI assistant section: shown only if the user has configured it in settings. */
@Composable
private fun AiBox(vm: AppViewModel, id: String, onRegenerate: (AiKind) -> Unit) {
    val run = vm.aiRun
    val mine = run?.lectureId == id
    // Which documents already exist (recomputed when one is created or edited)
    val existing = remember(id, vm.aiVersion, run) { AiKind.entries.filter { vm.aiFile(id, it).exists() } }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_section), fontWeight = FontWeight.SemiBold)
            if (mine && run != null) {
                KeepScreenOn()
                Text(stringResource(R.string.ai_running, run.step.coerceAtLeast(1), run.total), fontSize = 16.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.ai_running_hint), color = Color.Gray, fontSize = 13.sp)
                TextButton(onClick = { vm.cancelAi() }) { Text(stringResource(R.string.ai_stop)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kind in AiKind.entries) {
                        OutlinedButton(
                            enabled = run == null,
                            onClick = { if (kind in existing) onRegenerate(kind) else vm.runAi(id, kind) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(if (kind == AiKind.SUMMARY) R.string.ai_summarize else R.string.ai_clean)) }
                    }
                }
                for (kind in existing) {
                    TextButton(onClick = { vm.screen = Screen.AiDoc(id, kind) }) {
                        Text(stringResource(aiDocTitle(kind)) + " ›", fontSize = 16.sp)
                    }
                }
                Text(stringResource(R.string.ai_privacy, vm.aiConfig.host), color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}
