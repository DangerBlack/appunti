package org.appunti.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.appunti.app.R
import org.appunti.app.ai.AiKind

fun aiDocTitle(kind: AiKind) = when (kind) {
    AiKind.SUMMARY -> R.string.ai_doc_summary
    AiKind.CLEAN -> R.string.ai_doc_clean
}

/** Assistant-generated document: can be read, copied, shared and edited. */
@Composable
fun AiDocScreen(vm: AppViewModel, id: String, kind: AiKind) {
    val c = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lectures by vm.lectures.collectAsState()
    val lecture = lectures.firstOrNull { it.id == id }
    var text by remember(id, kind) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id, kind, vm.aiVersion) { text = vm.readAiDoc(id, kind) }
    val title = stringResource(aiDocTitle(kind))

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = { vm.open(id) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                lecture?.let { Text(it.title, color = Color.Gray, fontSize = 13.sp, maxLines = 1) }
            }
            if (editing == null) IconButton(onClick = { editing = text.orEmpty() }) { Icon(Icons.Default.Edit, stringResource(R.string.ai_edit)) }
        }

        val draft = editing
        if (draft != null) {
            // Editing the whole document
            OutlinedTextField(
                value = draft, onValueChange = { editing = it },
                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 23.sp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { vm.saveAiDoc(id, kind, draft); text = draft; editing = null }) { Text(stringResource(R.string.save)) }
            }
        } else {
            val body = text.orEmpty()
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(body))
                    Toast.makeText(c, c.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.copy_all)) }
                Button(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "${lecture?.title.orEmpty()} - $title")
                        .putExtra(Intent.EXTRA_TEXT, body)
                    c.startActivity(Intent.createChooser(i, c.getString(R.string.share)))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null); Text("  " + stringResource(R.string.send_text))
                }
            }
            Text(
                stringResource(R.string.ai_note, vm.aiConfig.model.ifBlank { "AI" }),
                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Simple line-by-line rendering: "# heading", "- bullet point", plain text
            val lines = remember(body) { body.lines().map { it.trimEnd() }.filter { it.isNotBlank() } }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(lines) { _, line ->
                    val t = line.trimStart()
                    SelectionContainer {
                        when {
                            t.startsWith("#") -> Text(
                                t.trimStart('#', ' ').replace("**", ""), fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                                lineHeight = 25.sp, modifier = Modifier.padding(top = 12.dp),
                            )
                            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> Row {
                                Text("•", fontSize = 16.sp, modifier = Modifier.padding(start = if (line.startsWith(" ")) 20.dp else 4.dp, end = 10.dp))
                                Text(t.drop(2).replace("**", ""), fontSize = 16.sp, lineHeight = 23.sp)
                            }
                            else -> Text(t.replace("**", ""), fontSize = 16.sp, lineHeight = 23.sp)
                        }
                    }
                }
            }
        }
    }
}
