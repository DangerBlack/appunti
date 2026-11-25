package org.appunti.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appunti.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Optional configuration for an OpenAI-API-compatible service (cloud or local). */
/**
 * [extra] = optional JSON parameters merged into the request, for advanced users.
 * Example for vLLM/Qwen: {"chat_template_kwargs": {"enable_thinking": false}}
 */
data class AiConfig(val endpoint: String, val apiKey: String, val model: String, val extra: String = "") {
    val isConfigured get() = endpoint.isNotBlank() && model.isNotBlank()

    /** The extra parameters as JSON, or null if the field is empty. Throws if it is not valid JSON. */
    fun extraJson(): JSONObject? = extra.trim().takeIf { it.isNotEmpty() }
        // Keyboards sometimes turn " into typographic quotes: convert them back to straight ones
        ?.replace(Regex("[\u201C\u201D\u201E\u00AB\u00BB]"), "\"")
        ?.let { JSONObject(it) }

    /** Host shown to the user ("the text is sent to ..."). */
    val host: String get() = runCatching { URL(chatUrl()).host }.getOrDefault(endpoint)

    /** Accepts both ".../v1" and the full URL ".../v1/chat/completions". */
    fun chatUrl(): String {
        val e = endpoint.trim().trimEnd('/')
        return if (e.endsWith("/chat/completions")) e else "$e/chat/completions"
    }

    companion object {
        private const val PREFS = "ai"

        fun load(c: Context): AiConfig {
            val p = c.getSharedPreferences(PREFS, 0)
            return AiConfig(p.getString("endpoint", "")!!, p.getString("key", "")!!, p.getString("model", "")!!, p.getString("extra", "")!!)
        }

        fun save(c: Context, cfg: AiConfig) {
            c.getSharedPreferences(PREFS, 0).edit()
                .putString("endpoint", cfg.endpoint.trim())
                .putString("key", cfg.apiKey.trim())
                .putString("model", cfg.model.trim())
                .putString("extra", cfg.extra.trim())
                .apply()
        }
    }
}

/** Error to show to the user: [messageRes] is the localized text, [detail] the technical detail. */
class AiException(val messageRes: Int, val detail: String? = null) : Exception(detail)

object AiClient {

    /** A single chat request. Returns the response text. */
    suspend fun chat(cfg: AiConfig, system: String, user: String, maxTokens: Int? = null): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", cfg.model.trim())
            .put("temperature", 0.3)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        if (maxTokens != null) body.put("max_tokens", maxTokens)
        val extra = try { cfg.extraJson() } catch (e: Exception) { throw AiException(R.string.ai_extra_invalid, e.message) }
        extra?.keys()?.forEach { k -> body.put(k, extra.get(k)) }

        val conn = try {
            URL(cfg.chatUrl()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException(R.string.ai_err_url, e.message)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 10 * 60_000 // "reasoning" models can take minutes
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (cfg.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey.trim()}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw httpError(code, text)

            val choice = runCatching { JSONObject(text).getJSONArray("choices").getJSONObject(0) }
                .getOrElse { throw AiException(R.string.ai_err_response, text.take(300)) }
            val msg = choice.optJSONObject("message")
            // A "null" or empty response must never end up in the document
            val content = if (msg == null || msg.isNull("content")) "" else msg.optString("content")
            val finish = choice.optString("finish_reason")
            if (finish == "length") throw AiException(R.string.ai_err_truncated, "finish_reason=length")
            if (content.isBlank()) throw AiException(R.string.ai_err_empty, "finish_reason=$finish")
            // Some local models prefix their "reasoning" between <think> ... </think>
            content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        } catch (e: AiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiException(R.string.ai_err_timeout, e.message)
        } catch (e: IOException) {
            throw AiException(R.string.ai_err_network, e.message)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpError(code: Int, body: String): AiException {
        val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: body.take(300)
        val tooLong = Regex("context|too long|maximum.*tokens|token limit", RegexOption.IGNORE_CASE).containsMatchIn(msg)
        val res = when {
            code == 401 || code == 403 -> R.string.ai_err_auth
            code == 404 -> R.string.ai_err_not_found
            code == 429 -> R.string.ai_err_rate
            code == 413 || tooLong -> R.string.ai_err_too_long
            else -> R.string.ai_err_generic
        }
        return AiException(res, "HTTP $code: $msg")
    }
}
