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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (raw in md.lines()) {
            val t = raw.trim()
            when {
                t.isEmpty() -> Spacer(Modifier.height(2.dp))
                t.startsWith("### ") -> Text(inline(t.drop(4)), style = MaterialTheme.typography.titleSmall)
                t.startsWith("## ") -> Text(inline(t.drop(3)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                t.startsWith("# ") -> Text(inline(t.drop(2)), style = MaterialTheme.typography.headlineSmall)
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
