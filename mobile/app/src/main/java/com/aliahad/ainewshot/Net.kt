// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Li Labs (LiLabsAI)

package com.aliahad.ainewshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
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

    private val providerClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        // DeepSeek owns bounded retries; never silently replay a billed POST here.
        .retryOnConnectionFailure(false)
        .build()

    /** POST and hand each SSE event (including [DONE]) to [onData]. */
    suspend fun postStream(url: String, headers: Map<String, String>, body: JSONObject, timeoutMs: Int = 120_000, onData: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", UA)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            val call = providerClient.newBuilder()
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build().newCall(request)
            coroutineScope {
                // OkHttp cancellation closes the socket without waiting for a blocked reader's lock.
                val abort = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                val context = coroutineContext
                try {
                    call.execute().use { response ->
                        if (response.code != 200) throw postError(response.code, response.header("Retry-After"))
                        response.body.charStream().buffered().use { reader ->
                            Sse.read(reader) { payload ->
                                context.ensureActive()
                                onData(payload)
                            }
                        }
                    }
                } catch (e: IOException) {
                    context.ensureActive()
                    throw e
                } finally { abort.cancel(); call.cancel() }
            }
        }

    private fun postError(code: Int, retryAfter: String?): HttpError {
        val message = when (code) {
            401, 403 -> "API key rejected. Check Me › Settings › API keys."
            402 -> "Insufficient API balance. Top up your provider account."
            429 -> "Provider rate limit reached. Try again shortly."
            else -> "Provider request failed. Try again or check your model settings."
        }
        val retry = retryAfter?.let { value ->
            value.toLongOrNull() ?: runCatching {
                val at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                ((at - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            }.getOrNull()
        }
        return HttpError(code, message, retry)
    }
}
