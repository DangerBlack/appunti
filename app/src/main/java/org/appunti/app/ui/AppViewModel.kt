package org.appunti.app.ui

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import org.appunti.app.R
import org.appunti.app.ai.AiClient
import org.appunti.app.ai.AiConfig
import org.appunti.app.ai.AiException
import org.appunti.app.ai.AiKind
import org.appunti.app.ai.AiTasks
import org.appunti.app.asr.ModelStore
import org.appunti.app.asr.TranscriptionService
import org.appunti.app.data.Audio
import org.appunti.app.data.Exporter
import org.appunti.app.data.Lecture
import org.appunti.app.data.Lectures
import org.appunti.app.data.Paragraph
import org.appunti.app.data.Search
import org.appunti.app.data.Status
import org.appunti.app.data.Transcript
import org.appunti.app.record.RecorderService
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Recording : Screen
    data class Detail(val id: String) : Screen
    data object Settings : Screen
    data class AiDoc(val id: String, val kind: AiKind) : Screen
}

/** A search result: the lecture, how many times the query appears in the text, and a snippet. */
data class SearchHit(val lecture: Lecture, val count: Int, val snippet: String?)

/** AI generation in progress: which lecture, what is being generated, and at which step. */
data class AiRun(val lectureId: String, val kind: AiKind, val step: Int, val total: Int)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    val lectures = Lectures.all
    val rec = RecorderService.state
    val tr = TranscriptionService.state
    val model = ModelStore.state

    fun retryModel() = ModelStore.ensure(app)
    fun modelOverMobile() = ModelStore.useMobileData(app)

    var screen by mutableStateOf<Screen>(if (RecorderService.state.value.active) Screen.Recording else Screen.Home)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun startRecording() {
        // While recording, the phone must not also transcribe: transcription resumes afterwards.
        if (tr.value.running) TranscriptionService.cancel(app)
        RecorderService.start(app)
        screen = Screen.Recording
    }

    fun open(id: String) { screen = Screen.Detail(id) }
    fun home() { screen = Screen.Home }

    fun transcribe(id: String) {
        if (rec.value.active) {
            message = app.getString(R.string.finish_recording_first)
            return
        }
        TranscriptionService.start(app, id)
    }

    fun cancelTranscription() = TranscriptionService.cancel(app)

    fun rename(id: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val old = Lectures.get(id) ?: return@launch
            val l = Lectures.update(id) { it.copy(title = t) } ?: return@launch
            // The .txt starts with the title: regenerate it, then move everything to the folder with the new name
            Transcript.rebuild(app, l, export = false)
            val texts = listOf(Lectures.TXT, Lectures.SRT) + AiKind.entries.map { it.file }
            Exporter.rename(app, old, l, texts.filter { File(Lectures.dir(id), it).exists() })
        }
    }

    /** Saves a paragraph correction (text equal to the original = no correction). */
    fun editParagraph(id: String, p: Paragraph, newText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = Lectures.dir(id)
            val edits = Transcript.readEdits(dir).toMutableMap()
            val t = newText.trim()
            if (t.isEmpty() || t == p.original.trim()) edits.remove(p.key) else edits[p.key] = t
            Transcript.writeEdits(dir, edits)
            Lectures.get(id)?.let { Transcript.rebuild(app, it) }
            editsVersion++
        }
    }

    /** "Fix a word everywhere": returns how many replacements were made. */
    suspend fun replaceAll(id: String, find: String, replacement: String): Int = withContext(Dispatchers.IO) {
        val dir = Lectures.dir(id)
        val edits = Transcript.readEdits(dir).toMutableMap()
        val ps = Transcript.paragraphs(Transcript.readSegments(dir), edits)
        val n = Transcript.replaceAll(ps, find, replacement, edits)
        if (n > 0) {
            Transcript.writeEdits(dir, edits)
            Lectures.get(id)?.let { Transcript.rebuild(app, it) }
            editsVersion++
        }
        n
    }

    /** Deletes the audio (from Documents) and keeps only the text. */
    fun deleteAudio(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val l = Lectures.get(id) ?: return@launch
            Audio.delete(app, l)
            Lectures.update(id) { it.copy(audioFile = "", audioUri = "") }
        }
    }

    // ---- Search ----

    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    /** Text (normalized and original) of each lecture; rebuilt only when lectures or corrections change. */
    private var index: Map<String, Pair<String, String>> = emptyMap()
    private var indexKey: Any? = null
    private var searchJob: Job? = null

    fun search(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { results = emptyList(); return }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val all = Lectures.all.value
            val key = all to editsVersion
            if (key != indexKey) {
                index = all.associate { l ->
                    val dir = Lectures.dir(l.id)
                    val text = Transcript.paragraphs(Transcript.readSegments(dir), Transcript.readEdits(dir)).joinToString("\n") { it.text }
                    l.id to (Search.norm(text) to text)
                }
                indexKey = key
            }
            results = all.mapNotNull { l ->
                val (norm, text) = index[l.id] ?: ("" to "")
                val inTitle = Search.count(Search.norm(l.title), q) > 0
                val n = Search.count(norm, q)
                if (!inTitle && n == 0) null else SearchHit(l, n, Search.snippet(text, norm, q))
            }
        }
    }

    // ---- Storage usage ----

    /** Size of each lecture: id -> (audio, texts) in bytes. */
    var sizes by mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap())
        private set
    var freeBytes by mutableStateOf(0L)
        private set

    fun refreshSizes() {
        viewModelScope.launch(Dispatchers.IO) {
            sizes = Lectures.all.value.associate { l ->
                val dir = Lectures.dir(l.id)
                val texts = dir.listFiles().orEmpty().filter { it.name != l.audioFile }.sumOf { it.length() }
                l.id to (Audio.size(app, l) to texts)
            }
            freeBytes = android.os.StatFs(app.filesDir.path).availableBytes
        }
    }

    // ---- AI assistant (optional) ----

    var aiConfig by mutableStateOf(AiConfig.load(app))
        private set
    var aiRun by mutableStateOf<AiRun?>(null)
        private set
    /** Changes whenever an AI document is generated or edited. */
    var aiVersion by mutableStateOf(0)
        private set
    private var aiJob: Job? = null

    fun saveAiConfig(cfg: AiConfig) {
        AiConfig.save(app, cfg)
        aiConfig = AiConfig.load(app)
    }

    /** Connection test: returns the model's reply or the error message. */
    suspend fun testAi(cfg: AiConfig): Result<String> = runCatching {
        AiClient.chat(cfg, "Reply briefly.", "Reply only with the word OK.", maxTokens = 2000)
    }.recoverCatching { e -> throw IllegalStateException(aiErrorText(e)) }

    fun aiErrorText(e: Throwable): String =
        if (e is AiException) app.getString(e.messageRes) + (e.detail?.let { "\n\n" + app.getString(R.string.ai_err_detail, it) } ?: "")
        else app.getString(R.string.ai_err_generic) + "\n\n" + app.getString(R.string.ai_err_detail, e.message ?: e.javaClass.simpleName)

    fun aiFile(id: String, kind: AiKind) = File(Lectures.dir(id), kind.file)

    fun runAi(id: String, kind: AiKind) {
        if (aiRun != null) return
        val cfg = aiConfig
        aiRun = AiRun(id, kind, 0, 1)
        aiJob = viewModelScope.launch {
            try {
                val l = Lectures.get(id) ?: return@launch
                val text = AiTasks.run(kind, cfg, l.title, paragraphs(id)) { step, total -> aiRun = AiRun(id, kind, step, total) }
                withContext(Dispatchers.IO) {
                    aiFile(id, kind).writeText(text)
                    Exporter.export(app, l, listOf(kind.file))
                }
                aiVersion++
                screen = Screen.AiDoc(id, kind)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = aiErrorText(e)
            } finally {
                aiRun = null
            }
        }
    }

    fun cancelAi() {
        aiJob?.cancel()
        aiRun = null
    }

    suspend fun readAiDoc(id: String, kind: AiKind): String =
        withContext(Dispatchers.IO) { aiFile(id, kind).takeIf { it.exists() }?.readText().orEmpty() }

    fun saveAiDoc(id: String, kind: AiKind, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            aiFile(id, kind).writeText(text)
            Lectures.get(id)?.let { Exporter.export(app, it, listOf(kind.file)) }
            aiVersion++
        }
    }

    /** Changes on every correction, so the screen reloads the paragraphs. */
    var editsVersion by mutableStateOf(0)

    fun delete(id: String) {
        Lectures.delete(id)
        screen = Screen.Home
    }

    fun clearError(id: String) {
        viewModelScope.launch(Dispatchers.IO) { Lectures.update(id) { it.copy(error = null) } }
    }

    suspend fun paragraphs(id: String): List<Paragraph> = withContext(Dispatchers.IO) {
        val dir = Lectures.dir(id)
        Transcript.paragraphs(Transcript.readSegments(dir), Transcript.readEdits(dir))
    }

    /** Imports audio recorded with another app ("Import" button or "Share"). */
    fun import(uri: Uri) {
        busy = app.getString(R.string.importing)
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    val cr = app.contentResolver
                    var name = "audio"
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { name = it }
                    }
                    val ext = name.substringAfterLast('.', "").lowercase().ifEmpty {
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri)) ?: "m4a"
                    }
                    val l = Lectures.create(name.substringBeforeLast('.').ifBlank { null })
                    // Copy straight into Documents: it is the only copy of the audio
                    val audio = cr.openInputStream(uri)!!.use { Exporter.writeAudio(app, l, ext, it) }
                    val dur = audio?.let { a ->
                        MediaMetadataRetriever().run {
                            try { setDataSource(app, a); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
                            catch (e: Exception) { 0L }
                            finally { release() }
                        }
                    } ?: 0L
                    if (audio == null || dur <= 0) {
                        audio?.let { runCatching { cr.delete(it, null, null) } }
                        Lectures.delete(l.id)
                        null
                    } else {
                        Lectures.save(l.copy(audioUri = audio.toString(), durationMs = dur, status = Status.RECORDED))
                        l.id
                    }
                }.getOrNull()
            }
            busy = null
            if (id == null) message = app.getString(R.string.import_failed)
            else screen = Screen.Detail(id)
        }
    }
}
