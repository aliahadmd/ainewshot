package com.aliahad.ainewshot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random
import kotlin.coroutines.coroutineContext

internal fun interface CompletionStream {
    suspend fun send(url: String, headers: Map<String, String>, body: JSONObject, onData: (String) -> Unit)
}

/** Direct DeepSeek completions. Streaming also avoids buffering long podcast scripts on the server. */
class DeepSeek internal constructor(
    private val key: String,
    private val model: String = FLASH,
    private val transport: CompletionStream = CompletionStream { url, headers, body, onData ->
        Net.postStream(url, headers, body, timeoutMs = 600_000, onData = onData)
    },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun chat(system: String, user: String, json: Boolean, maxTokens: Int = 6000): String =
        withTimeout(660_000) { complete(system, user, json, maxTokens, null) }

    suspend fun chatStream(system: String, user: String, maxTokens: Int, onText: (String) -> Unit): String =
        withTimeout(660_000) { complete(system, user, false, maxTokens, onText) }

    private suspend fun complete(system: String, user: String, json: Boolean, maxTokens: Int, onText: ((String) -> Unit)?): String {
        if (key.isBlank()) throw HttpError(401, "Add a DeepSeek API key in Me › Settings › API keys.")
        require(model in models) { "Unsupported DeepSeek model" }
        require(maxTokens in 1..393_216) { "Invalid DeepSeek output budget" }
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        repeat(3) { attempt ->
            var displayed = false
            try {
                val text = StringBuilder()
                var finish: String? = null
                var done = false
                gate.withPermit {
                    transport.send(URL, mapOf("Authorization" to "Bearer ${key.trim()}"), body) { payload ->
                        if (payload == "[DONE]") { done = true; return@send }
                        val event = JSONObject(payload)
                        if (event.has("error")) throw HttpError(502, "DeepSeek reported a stream error; try again later.")
                        val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: return@send
                        choice.str("finish_reason")?.let { finish = it }
                        // reasoning_content is deliberately excluded from the displayed answer.
                        choice.optJSONObject("delta")?.str("content")?.let { delta ->
                            text.append(delta)
                            if (onText != null && text.isNotBlank()) {
                                displayed = true
                                onText(text.toString())
                            }
                        }
                    }
                }
                if (!done || finish == null) throw IOException("DeepSeek stream ended before the answer was complete.")
                when (finish) {
                    "stop" -> Unit
                    "length" -> throw CompletionFailure("DeepSeek reached the output limit. Try a shorter episode or article piece.")
                    "insufficient_system_resource" -> throw HttpError(503, "DeepSeek is busy; try again shortly.")
                    else -> throw CompletionFailure("DeepSeek could not complete the answer ($finish).")
                }
                val answer = text.toString().trim()
                if (answer.isBlank()) throw EmptyCompletion()
                if (json) {
                    try { JSONObject(answer) } catch (_: org.json.JSONException) {
                        throw EmptyCompletion("DeepSeek returned invalid JSON.")
                    }
                }
                return answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                val transient = e is EmptyCompletion || e is IOException || (e is HttpError && e.code in retryable)
                // Never replay a stream after words have reached the reader, or retry rejected keys/billing.
                if (!transient || displayed || attempt == 2 || (e is HttpError && (e.retryAfter ?: 0) > 60)) throw e
                pause((e as? HttpError)?.retryAfter?.times(1000) ?: ((1000L shl attempt) + Random.nextLong(250)))
            }
        }
        error("Unreachable")
    }

    companion object {
        const val URL = "https://api.deepseek.com/chat/completions"
        const val FLASH = "deepseek-flash"
        const val PRO = "deepseek-v4-pro"
        val models = listOf(FLASH, PRO)
        private val gate = Semaphore(4)
        private val retryable = setOf(429, 500, 502, 503, 504)
    }
}

private class CompletionFailure(message: String) : Exception(message)
private class EmptyCompletion(message: String = "DeepSeek returned an empty answer; try again.") : Exception(message)
