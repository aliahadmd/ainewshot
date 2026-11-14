// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Li Labs (LiLabsAI)

package com.aliahad.ainewshot

import org.junit.Assert.assertEquals
import org.junit.Test

class SseTest {
    @Test fun keepAlivesCrLfMultilineAndTerminalMarkerAreHandled() {
        val text = ": keep-alive\r\n\r\nevent: message\r\ndata: first\r\ndata: second\r\n\r\ndata: [DONE]\r\n\r\ndata: ignored\r\n\r\n"
        val seen = mutableListOf<String>()
        Sse.read(text.reader().buffered()) { seen += it }
        assertEquals(listOf("first\nsecond", "[DONE]"), seen)
    }

    @Test fun finalUnterminatedEventIsDispatched() {
        val seen = mutableListOf<String>()
        Sse.read("data: final".reader().buffered()) { seen += it }
        assertEquals(listOf("final"), seen)
    }
}
