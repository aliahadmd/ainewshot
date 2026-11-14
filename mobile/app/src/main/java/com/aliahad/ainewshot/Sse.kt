// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Li Labs (LiLabsAI)

package com.aliahad.ainewshot

import java.io.BufferedReader

/** SSE framing: comments are keep-alives, and multiple data lines belong to one event. */
internal object Sse {
    fun read(reader: BufferedReader, onData: (String) -> Unit) {
        val data = mutableListOf<String>()
        fun dispatch(): Boolean {
            if (data.isEmpty()) return false
            val payload = data.joinToString("\n")
            data.clear()
            onData(payload)
            return payload == "[DONE]"
        }
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> if (dispatch()) return
                line.startsWith("data:") -> data += line.substring(5).removePrefix(" ")
            }
        }
        dispatch()
    }
}
