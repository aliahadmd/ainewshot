package com.aliahad.ainewshot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeepSeekTest {
    private data class Request(val url: String, val headers: Map<String, String>, val body: JSONObject)
    private class FakeStream(val respond: suspend (Int, (String) -> Unit) -> Unit) : CompletionStream {
        val requests = mutableListOf<Request>()
        override suspend fun send(url: String, headers: Map<String, String>, body: JSONObject, onData: (String) -> Unit) {
            requests += Request(url, headers, body)
            respond(requests.size, onData)
        }
    }

    private fun chunk(text: String? = null, reason: String? = null, reasoning: String? = null): String =
        JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("content", text ?: JSONObject.NULL).put("reasoning_content", reasoning ?: JSONObject.NULL))
            .put("finish_reason", reason ?: JSONObject.NULL))).toString()

    private fun answer(emit: (String) -> Unit, text: String = "OK", reason: String = "stop", done: Boolean = true) {
        emit(chunk(text)); emit(chunk(reason = reason))
        if (done) emit("[DONE]")
    }

    private fun client(fake: FakeStream, model: String = DeepSeek.FLASH, pause: suspend (Long) -> Unit = {}) =
        DeepSeek("test-key", model, fake, pause)

    private suspend inline fun <reified T : Throwable> fails(block: suspend () -> Unit): T {
        try { block() } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${e::class.java.simpleName}", e)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun directRequestUsesBearerAndExplicitNonThinkingJsonMode() { runBlocking {
        val fake = FakeStream { _, emit -> answer(emit, "{\"items\":[]}") }
        client(fake).chat("Return JSON: {items: []}", "news", true)
        val r = fake.requests.single()
        assertEquals("https://api.deepseek.com/chat/completions", r.url)
        assertEquals(mapOf("Authorization" to "Bearer test-key"), r.headers)
        assertEquals(DeepSeek.FLASH, r.body.getString("model"))
        assertEquals("disabled", r.body.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", r.body.getJSONObject("response_format").getString("type"))
        assertTrue(r.body.getBoolean("stream"))
        assertEquals(0.2, r.body.getDouble("temperature"), 0.0)
        assertEquals("news", r.body.getJSONArray("messages").getJSONObject(1).getString("content"))
    } }

    @Test fun proWriterKeepsItsFullOutputBudget() { runBlocking {
        val fake = FakeStream { _, emit -> answer(emit, "{\"lines\":[]}") }
        client(fake, DeepSeek.PRO).chat("JSON script", "stories", true, maxTokens = 24_000)
        assertEquals(DeepSeek.PRO, fake.requests.single().body.getString("model"))
        assertEquals(24_000, fake.requests.single().body.getInt("max_tokens"))
    } }

    @Test fun streamingDisplaysOnlyAnswerContentInOrder() { runBlocking {
        val fake = FakeStream { _, emit ->
            emit(chunk(reasoning = "private reasoning"))
            emit(chunk("Hello")); emit(chunk(" world")); emit(chunk(reason = "stop")); emit("[DONE]")
        }
        val visible = mutableListOf<String>()
        assertEquals("Hello world", client(fake).chatStream("translate", "news", 100) { visible += it })
        assertEquals(listOf("Hello", "Hello world"), visible)
        assertFalse(fake.requests.single().body.has("response_format"))
    } }

    @Test fun emptyJsonAndInvalidJsonAreRetriedBeforeAcceptance() { runBlocking {
        val fake = FakeStream { attempt, emit -> answer(emit, when (attempt) { 1 -> "   "; 2 -> "not JSON"; else -> "{\"ok\":true}" }) }
        assertEquals("{\"ok\":true}", client(fake).chat("JSON", "news", true))
        assertEquals(3, fake.requests.size)
    } }

    @Test fun repeatedEmptyAnswersStopAfterThreeRequests() { runBlocking {
        val fake = FakeStream { _, emit -> answer(emit, " ") }
        fails<Exception> { client(fake).chat("JSON", "news", true) }
        assertEquals(3, fake.requests.size)
    } }

    @Test fun rateLimitHonorsRetryAfter() { runBlocking {
        val pauses = mutableListOf<Long>()
        val fake = FakeStream { attempt, emit -> if (attempt == 1) throw HttpError(429, "busy", 2) else answer(emit) }
        assertEquals("OK", client(fake, pause = { pauses += it }).chat("test", "ping", false))
        assertEquals(listOf(2000L), pauses)
    } }

    @Test fun longRetryAfterFailsWithoutRequestingTooSoon() { runBlocking {
        val fake = FakeStream { _, _ -> throw HttpError(429, "busy", 120) }
        fails<HttpError> { client(fake).chat("test", "ping", false) }
        assertEquals(1, fake.requests.size)
    } }

    @Test fun invalidCredentialsBalanceAndParametersAreNotRetried() { runBlocking {
        for (status in listOf(400, 401, 402, 403, 422)) {
            val fake = FakeStream { _, _ -> throw HttpError(status, "rejected") }
            assertEquals(status, fails<HttpError> { client(fake).chat("test", "ping", false) }.code)
            assertEquals(1, fake.requests.size)
        }
    } }

    @Test fun truncatedOutputIsNeverReturnedAsSuccess() { runBlocking {
        val fake = FakeStream { _, emit -> answer(emit, "{\"items\":[]}", "length") }
        val error = fails<Exception> { client(fake).chat("JSON", "news", true) }
        assertTrue(error.message.orEmpty().contains("output limit"))
        assertEquals(1, fake.requests.size)
    } }

    @Test fun missingTerminalMarkerIsNotAcceptedEvenAfterStop() { runBlocking {
        val fake = FakeStream { _, emit -> answer(emit, done = false) }
        fails<IOException> { client(fake).chat("test", "ping", false) }
        assertEquals(3, fake.requests.size)
    } }

    @Test fun displayedPartialAnswersAreNotReplayedOnDisconnect() { runBlocking {
        val fake = FakeStream { _, emit -> emit(chunk("Partial")); throw IOException("disconnected") }
        val visible = mutableListOf<String>()
        fails<IOException> { client(fake).chatStream("test", "ping", 100) { visible += it } }
        assertEquals(listOf("Partial"), visible)
        assertEquals(1, fake.requests.size)
    } }

    @Test fun providerErrorFramesAreFailures() { runBlocking {
        val fake = FakeStream { _, emit -> emit("{\"error\":{\"message\":\"failed\"}}") }
        fails<HttpError> { client(fake).chat("test", "ping", false) }
        assertEquals(3, fake.requests.size)
    } }

    @Test fun cancellationIsPropagatedWithoutRetry() { runBlocking {
        val fake = FakeStream { _, _ -> throw CancellationException("cancelled") }
        fails<CancellationException> { client(fake).chat("test", "ping", false) }
        assertEquals(1, fake.requests.size)
    } }

    @Test fun missingKeyFailsBeforeTransport() { runBlocking {
        val fake = FakeStream { _, emit -> answer(emit) }
        fails<HttpError> { DeepSeek(" ", transport = fake).chat("test", "ping", false) }
        assertTrue(fake.requests.isEmpty())
    } }
}
