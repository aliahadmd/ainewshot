package com.aliahad.ainewshot.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun ago(t: Long?): String = if (t == null) "" else
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Text(text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

/** A horizontally scrolling single-choice chip row. */
@Composable
fun <T> ChoiceRow(label: String?, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (v, l) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l) }) }
        }
    }
}

/** Minimal block-level Markdown renderer with clickable links. */
@Composable
fun MarkdownText(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { mdBlocks(md).forEach { MarkdownBlock(it) } }
}

/** Lines, except that a ``` fenced block stays together (so long articles can be shown one block per list row). */
fun mdBlocks(md: String): List<String> {
    val out = mutableListOf<String>()
    var fence: StringBuilder? = null
    var table: StringBuilder? = null
    for (line in md.lines()) {
        // Consecutive "| a | b" rows form one table block.
        if (fence == null && line.trimStart().startsWith("|")) { (table ?: StringBuilder().also { table = it }).append(line).append('\n'); continue }
        table?.let { out += it.toString().trimEnd(); table = null }
        if (line.trim().startsWith("```")) {
            if (fence == null) { fence = StringBuilder(line).append('\n') } else { fence.append(line); out += fence.toString(); fence = null }
        } else if (fence != null) fence.append(line).append('\n')
        else out += line
    }
    fence?.let { out += it.toString() }
    table?.let { out += it.toString().trimEnd() }
    return out
}

fun isHeading(block: String) = block.trimStart().let { it.startsWith("# ") || it.startsWith("## ") || it.startsWith("### ") || it.startsWith("#### ") }

@Composable
fun MarkdownBlock(raw: String) {
    val t = raw.trim()
    when {
        t.isEmpty() -> Spacer(Modifier.height(2.dp))
        t.startsWith("```") -> Text(t.removePrefix("```").substringAfter('\n').removeSuffix("```").trimEnd(),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()).padding(10.dp))
        t.startsWith("|") -> MarkdownTable(t)
        t.startsWith("#### ") -> Text(inline(t.drop(5)), style = MaterialTheme.typography.titleSmall)
        t.startsWith("### ") -> Text(inline(t.drop(4)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        t.startsWith("## ") -> Text(inline(t.drop(3)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        t.startsWith("# ") -> Text(inline(t.drop(2)), style = MaterialTheme.typography.headlineSmall)
        t.startsWith("> ") -> Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.outline))
            Text(inline(t.drop(2)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        }
        t.startsWith("- ") || t.startsWith("* ") -> Row {
            Text("•  "); Text(inline(t.drop(2)), style = MaterialTheme.typography.bodyMedium)
        }
        t == "---" -> HorizontalDivider()
        t.startsWith("【") || (t.startsWith("[") && t.endsWith("]") && !t.contains("](")) ->
            Text(t.trim('【', '】', '[', ']'), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        else -> Text(inline(t), style = MaterialTheme.typography.bodyMedium,
            color = if (raw.startsWith("   ")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = if (raw.startsWith("   ")) 18.dp else 0.dp))
    }
}

@Composable
private fun MarkdownTable(block: String) {
    val raw = block.lines().map { r -> r.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() } }
        .filterNot { r -> r.all { c -> c.isEmpty() || c.all { ch -> ch == '-' || ch == ':' } } }
    // Rows can disagree by a stray leading "|" (e.g. after translation): align them to the usual column count.
    val cols = raw.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: return
    val rows = raw.map { r -> var x = r; while (x.size > cols && x.first().isEmpty()) x = x.drop(1); x }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))) {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until cols) {
                    Text(inline(r.getOrElse(c) { "" }), style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (i == 0 || c == 0) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Converts **bold** and [text](url) to an AnnotatedString with real links. */
@Composable
private fun inline(s: String): AnnotatedString {
    val html = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
        .replace(Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)"""), "<a href=\"$2\">$1</a>")
    return AnnotatedString.fromHtml(html, linkStyles = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)))
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
        Box(Modifier.weight(1f)) { Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Suppress("unused")
fun bold(s: String) = buildAnnotatedString { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(s); pop() }
