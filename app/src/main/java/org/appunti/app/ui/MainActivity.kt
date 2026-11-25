package org.appunti.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.appunti.app.R
import org.appunti.app.record.RecorderService

class MainActivity : ComponentActivity() {

    companion object { const val EXTRA_LECTURE = "lecture" }

    private val vm: AppViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording()
        else vm.message = getString(R.string.perm_mic)
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.import(it) }
    }

    private fun record() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        } else {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                val rec by vm.rec.collectAsState()
                LaunchedEffect(rec.finishedId) {
                    rec.finishedId?.let { vm.open(it); RecorderService.consumeFinished() }
                }
                LaunchedEffect(rec.error) {
                    rec.error?.let { vm.message = it; vm.home(); RecorderService.consumeFinished() }
                }
                BackHandler(enabled = vm.screen != Screen.Home) {
                    when (val s = vm.screen) {
                        is Screen.AiDoc -> vm.open(s.id)
                        else -> vm.home()
                    }
                }

                when (val s = vm.screen) {
                    Screen.Home -> HomeScreen(vm, onRecord = ::record, onImport = { pickAudio.launch(arrayOf("audio/*")) })
                    Screen.Recording -> RecordScreen(vm)
                    is Screen.Detail -> LectureScreen(vm, s.id)
                    Screen.Settings -> SettingsScreen(vm)
                    is Screen.AiDoc -> AiDocScreen(vm, s.id, s.kind)
                }

                vm.message?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { vm.message = null },
                        confirmButton = { TextButton(onClick = { vm.message = null }) { Text(stringResource(android.R.string.ok)) } },
                        text = { Text(msg) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.retryModel() // if a download failed, retry as soon as the app returns to the foreground
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_LECTURE)?.let { id ->
            if (RecorderService.state.value.active && RecorderService.state.value.lectureId == id) vm.screen = Screen.Recording
            else vm.open(id)
        }
        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { vm.import(it) }
        }
    }
}
