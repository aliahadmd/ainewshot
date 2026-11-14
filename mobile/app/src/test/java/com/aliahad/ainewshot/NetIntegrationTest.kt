// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Li Labs (LiLabsAI)

package com.aliahad.ainewshot

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetIntegrationTest {
    private fun server() = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private fun url(server: HttpServer) = "http://127.0.0.1:${server.address.port}/completion"

    @Test fun directClientReadsRealHttpSseAndSendsBearerHeader() { runBlocking {
        val server = server()
        var authorization: String? = null
        var request: JSONObject? = null
        server.createContext("/completion") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = ": keep-alive\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val client = DeepSeek("test-key", transport = CompletionStream { _, headers, body, emit ->
                Net.postStream(url(server), headers, body, onData = emit)
            })
            assertEquals("OK", client.chat("test", "ping", false))
            assertEquals("Bearer test-key", authorization)
            assertEquals(DeepSeek.FLASH, request!!.getString("model"))
        } finally { server.stop(0) }
    } }

    @Test fun providerRedirectsDoNotForwardCredentials() { runBlocking {
        val server = server()
        val redirected = AtomicInteger()
        server.createContext("/completion") { exchange ->
            exchange.responseHeaders.add("Location", "/unexpected")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/unexpected") { exchange -> redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            try {
                Net.postStream(url(server), mapOf("Authorization" to "Bearer test-key"), JSONObject()) {}
                fail("A redirect must be rejected")
            } catch (e: HttpError) { assertEquals(302, e.code) }
            assertEquals(0, redirected.get())
        } finally { server.stop(0) }
    } }

    @Test fun retryAfterIsPreservedAndProviderErrorBodyIsNotExposed() { runBlocking {
        val server = server()
        server.createContext("/completion") { exchange ->
            exchange.responseHeaders.add("Retry-After", "7")
            exchange.sendResponseHeaders(429, 0)
            exchange.responseBody.use { it.write("sensitive upstream body".toByteArray()) }
        }
        server.start()
        try {
            try {
                Net.postStream(url(server), emptyMap(), JSONObject()) {}
                fail("Expected rate limit")
            } catch (e: HttpError) {
                assertEquals(429, e.code)
                assertEquals(7L, e.retryAfter)
                assertFalse(e.message.orEmpty().contains("sensitive upstream body"))
            }
        } finally { server.stop(0) }
    } }

    @Test fun cancellationClosesAWaitingStream() { runBlocking {
        val server = server()
        val waiting = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.createContext("/completion") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(": keep-alive\n\n".toByteArray())
                exchange.responseBody.flush()
                waiting.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try {
            withTimeout(3000) {
                val job = launch { Net.postStream(url(server), emptyMap(), JSONObject(), timeoutMs = 8000) {} }
                waiting.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        } finally { release.countDown(); server.stop(0) }
    } }
}
