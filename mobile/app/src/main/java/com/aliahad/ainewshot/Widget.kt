package com.aliahad.ainewshot

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.launch

/** Home-screen widget: newest first-hand headlines (one per event) and your unread count. Tap one to open it. */
object HeadlinesWidget {
    private val rows = listOf(
        Triple(R.id.row0, R.id.title0, R.id.meta0), Triple(R.id.row1, R.id.title1, R.id.meta1),
        Triple(R.id.row2, R.id.title2, R.id.meta2), Triple(R.id.row3, R.id.title3, R.id.meta3),
    )

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, HeadlinesWidgetReceiver::class.java))
        if (ids.isEmpty()) return
        NewsRepo.load(app)
        val items = NewsRepo.grouped(NewsRepo.recent.value.filter { it.kind == "news" }).map { it.first to it.second.size }.take(rows.size)
        val unread = NewsRepo.unread().size
        val read = NewsRepo.readIds.value
        val v = RemoteViews(app.packageName, R.layout.widget_headlines)
        v.setTextViewText(R.id.status, if (unread > 0) "$unread unread" else NewsRepo.status.value.lastCheck?.let {
            "Live · " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } ?: "")
        v.setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        v.setViewVisibility(R.id.empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
        rows.forEachIndexed { i, (row, title, meta) ->
            val pair = items.getOrNull(i)
            if (pair == null) { v.setViewVisibility(row, View.GONE); return@forEachIndexed }
            val (n, also) = pair
            val sources = also + n.extraSources
            v.setViewVisibility(row, View.VISIBLE)
            v.setTextViewText(title, (if (n.id !in read) "• " else "") + n.title)
            v.setTextViewText(meta, listOfNotNull(n.sourceName, if (sources > 0) "+$sources sources" else null,
                DateUtils.getRelativeTimeSpanString(n.publishedAt ?: n.discoveredAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)).joinToString(" · "))
            val open = Intent(app, MainActivity::class.java).putExtra("item", n.id).setData(Uri.parse("ainewshot://item/${n.id}"))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            v.setOnClickPendingIntent(row, PendingIntent.getActivity(app, 100 + i, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        mgr.updateAppWidget(ids, v)
    }
}

class HeadlinesWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        NewsRepo.appScope.launch { try { HeadlinesWidget.refresh(ctx) } finally { pending.finish() } }
    }
}
