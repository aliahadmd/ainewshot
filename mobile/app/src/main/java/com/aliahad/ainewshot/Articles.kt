package com.aliahad.ainewshot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full article text: AIHOT's HTML body -> Markdown -> English, cached on disk per item. */
object Articles {
    private fun dir(ctx: Context) = File(ctx.filesDir, "articles").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), id.filter { it.isLetterOrDigit() } + ".md")

    fun cached(ctx: Context, id: String): String? = file(ctx, id).takeIf { it.exists() }?.readText()

    suspend fun store(ctx: Context, id: String, md: String) = withContext(Dispatchers.IO) {
        runCatching {
            file(ctx, id).writeText(md)
            // Keep the newest 300 articles (~a few MB): enough for offline reading, never a growing archive (terms §7).
            dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.drop(300)?.forEach { it.delete() }
        }
    }

    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    /** One piece of an article being translated: text so far (null = not started) and whether it's finished. */
    data class Piece(val text: String?, val done: Boolean)

    private val inflight = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<Piece>>>()

    /** What to show: every finished piece, plus the one being written now (later pieces wait, so text never jumps). */
    fun visible(parts: List<Piece>): String {
        val out = mutableListOf<String>()
        for (p in parts) { p.text?.let { out += it }; if (!p.done) break }
        return out.joinToString("\n\n")
    }

    /** Translate [md] in the background (keeps going if you leave the article), streaming each piece, then cache it. */
    fun translate(ctx: Context, id: String, md: String): StateFlow<List<Piece>> = inflight.getOrPut(id) {
        val flow = MutableStateFlow<List<Piece>>(emptyList())
        NewsRepo.appScope.launch {
            try {
                val parts = Translator.article(md, Settings.get(ctx), onCount = { n -> flow.value = List(n) { Piece(null, false) } }) { i, t, done ->
                    flow.update { l -> if (i < l.size) l.toMutableList().also { it[i] = Piece(t, done) } else l }
                }
                store(ctx, id, parts.joinToString("\n\n"))
                flow.value = parts.map { Piece(it, true) }
            } finally { inflight.remove(id) }
        }
        flow
    }

    /** AIHOT's article HTML as the light Markdown our reader renders. */
    fun markdown(html: String): String {
        val pres = mutableListOf<String>()
        var s = Regex("(?is)<pre[^>]*>(.*?)</pre>").replace(html) { m ->
            pres += decode(m.groupValues[1].replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""))
            "\u0000PRE${pres.size - 1}\u0000"
        }
        s = s.replace(Regex("\\s*\\n\\s*"), " ")
        s = Regex("(?is)<(script|style|svg)[^>]*>.*?</\\1>").replace(s, "")
        s = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>").replace(s) { m ->
            val level = m.groupValues[1].toInt()
            "\n\n" + (if (level <= 2) "## " else "### ") + m.groupValues[2].replace(Regex("<[^>]+>"), "").trim() + "\n\n"
        }
        s = Regex("(?is)<a\\s[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>").replace(s) { m ->
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (text.isBlank()) "" else "[${text.replace("[", "(").replace("]", ")")}](${m.groupValues[1]})"
        }
        s = s.replace(Regex("(?i)<(strong|b)(\\s[^>]*)?>"), "**").replace(Regex("(?i)</(strong|b)>"), "**")
        s = s.replace(Regex("(?i)</?code[^>]*>"), "`")
        s = s.replace(Regex("(?i)<li[^>]*>"), "\n- ").replace(Regex("(?i)</li>"), "")
        s = s.replace(Regex("(?i)<blockquote[^>]*>"), "\n\n> ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<t[dh][^>]*>"), " | ").replace(Regex("(?i)</tr>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|ul|ol|blockquote|figure|figcaption|table|section|header)>"), "\n\n")
        s = s.replace(Regex("(?i)<img[^>]*>"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = decode(s)
        s = Regex("\u0000PRE(\\d+)\u0000").replace(s) { "\n\n```\n" + pres[it.groupValues[1].toInt()].trim('\n') + "\n```\n\n" }
        return s.lines().joinToString("\n") { if (it.startsWith("```") || it.isBlank()) it.trim() else it.trimEnd() }
            .replace(Regex("\\*\\*\\s*\\*\\*"), "")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun decode(s: String) = s
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
