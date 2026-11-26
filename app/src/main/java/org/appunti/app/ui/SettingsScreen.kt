package org.appunti.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.appunti.app.BuildConfig
import org.appunti.app.R
import org.appunti.app.ai.AiConfig

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(vm.aiConfig.endpoint) }
    var key by remember { mutableStateOf(vm.aiConfig.apiKey) }
    var model by remember { mutableStateOf(vm.aiConfig.model) }
    var extra by remember { mutableStateOf(vm.aiConfig.extra) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val cfg = AiConfig(endpoint, key, model, extra)
    val extraValid = remember(extra) { runCatching { cfg.extraJson() }.isSuccess }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = { vm.home() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.settings), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ai_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.ai_intro), color = Color.LightGray, fontSize = 14.sp)

            OutlinedTextField(
                value = endpoint, onValueChange = { endpoint = it; result = null }, singleLine = true,
                label = { Text(stringResource(R.string.ai_endpoint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it; result = null }, singleLine = true,
                label = { Text(stringResource(R.string.ai_key)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it; result = null }, singleLine = true,
                label = { Text(stringResource(R.string.ai_model)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = extra, onValueChange = { extra = it; result = null },
                label = { Text(stringResource(R.string.ai_extra)) },
                isError = !extraValid,
                supportingText = { if (!extraValid) Text(stringResource(R.string.ai_extra_invalid)) },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = cfg.isConfigured && extraValid && !testing,
                    onClick = {
                        testing = true
                        result = null
                        scope.launch {
                            val r = vm.testAi(cfg)
                            result = r.fold({ true to c.getString(R.string.ai_test_ok, it.take(60)) }, { false to (it.message ?: "") })
                            testing = false
                        }
                    },
                ) { Text(stringResource(if (testing) R.string.ai_testing else R.string.ai_test)) }
                Button(enabled = extraValid, onClick = {
                    vm.saveAiConfig(cfg)
                    Toast.makeText(c, c.getString(R.string.ai_saved), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.save)) }
            }
            if (testing) CircularProgressIndicator()
            result?.let { (ok, text) -> Text(text, color = if (ok) Color(0xFF81C784) else Color(0xFFFFB74D), fontSize = 14.sp) }

            if (vm.aiConfig.isConfigured) {
                Text(stringResource(R.string.ai_privacy, vm.aiConfig.host), color = Color.Gray, fontSize = 13.sp)
                TextButton(onClick = {
                    endpoint = ""; key = ""; model = ""; extra = ""; result = null
                    vm.saveAiConfig(AiConfig("", "", ""))
                }) { Text(stringResource(R.string.ai_clear), color = Color(0xFFEF9A9A)) }
            }

            val uri = LocalUriHandler.current
            TextButton(onClick = { uri.openUri(PRIVACY_URL) }) { Text(stringResource(R.string.privacy_policy)) }
            Text("Appunti ${BuildConfig.VERSION_NAME}", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

private const val PRIVACY_URL = "https://github.com/DangerBlack/appunti/blob/main/docs/PRIVACY.md"
