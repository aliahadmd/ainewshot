package com.aliahad.ainewshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class HttpError(val code: Int, val body: String, val retryAfter: Long? = null) :
    Exception("HTTP $code${if (body.isNotBlank()) ": " + body.take(200) else ""}")

object Net {
    // AIHOT asks programs that sync or push to identify themselves with aihot-api/<version>.
    val UA = "AINewsHot-Android/${BuildConfig.VERSION_NAME} aihot-api/2.0.0 (personal reader)"
    private val etags = ConcurrentHashMap<String, String>()

    /** GET text. With [conditional], sends the last ETag and returns null on 304 (nothing changed). */
    suspend fun get(url: String, conditional: Boolean = false): String? = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 40_000
            c.setRequestProperty("User-Agent", UA)
            if (conditional) etags[url]?.let { c.setRequestProperty("If-None-Match", it) }
            when (val code = c.responseCode) {
                200 -> {
                    if (conditional) c.getHeaderField("ETag")?.let { etags[url] = it }
                    c.inputStream.bufferedReader().use { it.readText() }
                }
                304 -> null
                429 -> throw HttpError(429, "rate limited", c.getHeaderField("Retry-After")?.toLongOrNull() ?: 60)
                else -> throw HttpError(code, c.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            }
        } finally {
            c.disconnect()
        }
    }

    /** POST with [headers]. Redirects are not followed: for the private backend a redirect means "sign in again". */
    private fun open(url: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = timeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", UA)
        }

    suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int = 180_000): JSONObject = withContext(Dispatchers.IO) {
        val c = open(url, headers, timeoutMs)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw HttpError(code, c.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** POST and hand every Server-Sent-Events `data:` payload to [onData]. */
    suspend fun postStream(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int = 120_000, onData: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val c = open(url, headers, timeoutMs)
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code != 200) throw HttpError(code, c.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        coroutineContext.ensureActive()
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload == "[DONE]") break
                        onData(payload)
                    }
                }
            } finally {
                c.disconnect()
            }
        }
}
