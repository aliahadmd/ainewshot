package com.aliahad.ainewshot.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons in the style aihot.news uses (Lucide: 24×24, 2px round strokes), drawn as vectors so they tint
 * with the theme like any Material icon. The app only bundles Material's core icon set.
 */
object AppIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(name: String, vararg paths: String, filled: Boolean = false, stroke: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    // Bottom tabs (the site's: 精选 ⚡, 热点 🔥, 日报 📰, … 我的 👤; plus Podcast)
    private const val ZAP = "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"
    private const val FLAME = "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"
    val Selected = icon("zap", ZAP)
    val SelectedOn = icon("zap-on", ZAP, filled = true)
    val Hot = icon("flame", FLAME)
    val HotOn = icon("flame-on", FLAME, filled = true)
    val Daily = icon("newspaper",
        "M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-2 2Zm0 0a2 2 0 0 1-2-2v-9c0-1.1.9-2 2-2h2",
        "M18 14h-8", "M15 18h-5", "M10 6h8v4h-8V6Z")
    val Podcast = icon("headphones",
        "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3")
    val Me = icon("user", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", circle(12f, 7f, 4f))

    // Reading toolbar and cards
    private const val BOOKMARK = "m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z"
    val Bookmark = icon("bookmark", BOOKMARK)
    val BookmarkOn = icon("bookmark-on", BOOKMARK, filled = true)
    val Contents = icon("list", "M3 12h.01", "M3 18h.01", "M3 6h.01", "M8 12h13", "M8 18h13", "M8 6h13")
    val Play = icon("play", "M6 3 20 12 6 21 6 3z")
    val Stop = icon("square", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", filled = true)
    val Share = icon("share", circle(18f, 5f, 3f), circle(6f, 12f, 3f), circle(18f, 19f, 3f),
        "m8.59 13.51 6.83 3.98", "m15.41 6.51-6.82 3.98")
    val External = icon("external-link", "M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")

    // Me
    val Topics = icon("hash", "M4 9h16", "M4 15h16", "M10 3 8 21", "M16 3l-2 18")
    val CatchUp = icon("history", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2")
    val Search = icon("search", circle(11f, 11f, 8f), "m21 21-4.3-4.3")
    val Ranking = icon("chart-column", "M3 3v16a2 2 0 0 0 2 2h16", "M18 17V9", "M13 17V5", "M8 17v-3")
    val Settings = icon("sliders", "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4")
    val Reliability = icon("shield-check",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 12 2 2 4-4")
    val Story = icon("activity", "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2")
    val Info = icon("info", circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")
    val Rules = icon("file-text", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8")
}
