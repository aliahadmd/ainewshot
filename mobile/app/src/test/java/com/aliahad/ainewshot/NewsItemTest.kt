package com.aliahad.ainewshot

import org.junit.Assert.*
import org.junit.Test

class NewsItemTest {
    private fun source(reason: String? = null) = ApiItem("item-1", "标题", null, "摘要", "Source", null, null, null, null, null, null, reason)

    @Test fun translationKeepsSpokenScriptAndRelevanceForCacheReuse() {
        val rendered = Rendered("Title", "Summary", "Why", "Spoken version", 91)
        val item = NewsItem.from(source("原因"), rendered)
        assertEquals("Spoken version", item.spoken)
        assertEquals("Spoken version", item.speechText)
        assertEquals(91, item.relevance)
        assertEquals("Why", item.why)
        assertEquals(item, NewsItem.fromJson(item.toJson()))
    }

    @Test fun absentSourceReasonCannotBecomeAnInventedReason() {
        val item = NewsItem.from(source(), Rendered("Title", "Summary", "Invented", "Speech", 70))
        assertNull(item.why)
    }
}
