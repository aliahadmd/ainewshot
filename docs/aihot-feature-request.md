# Request to AIHOT: official first-hand filter in the public API

**Send via:** https://aihot.news/feedback, or email wzglyay@virxact.com.
**Not sent yet.** Sending it is your call.

---

## 中文（发送用）

您好，AIHOT 团队：

我在做一个**个人、非商业**的安卓阅读器，只给自己用：把 AIHOT 的「精选 · 只看一手」翻译成英文，并在有新的一手发布时提醒我。已按 `llms.txt` 的要求使用 `/api/v1`、ETag/If-None-Match、≥60 秒轮询，并带 `aihot-api/2.0.0` User-Agent；撤选与更正通过 `selected/snapshot` + `selected/changes` 同步。

目前公开 API 里没有「只看一手」这个筛选，网页用的是 `/api/site/timeline?channel=firstParty`。为了不依赖站内接口，想请问能否：

1. 在 `/api/v1/items` 增加 `channel=firstParty`（与网页「只看一手」一致）；或
2. 在 `Item.source` 中返回 `firstParty: true/false`（`StoryReport.source` 已经有这个字段）。

另外，如果 `Item` 能带上 `group`（`factId`、`additionalSourceCount`）和 `tags`，客户端就能像网页一样把同一事件的多篇报道合并显示。

谢谢！这个项目让我不用刷社交媒体也能跟上 AI 新闻。

---

## English (for your reference)

Hello AIHOT team,

I'm building a **personal, non-commercial** Android reader for my own use. It translates AIHOT's "Selected · first-hand only" view into English and alerts me when a new first-hand post appears. As `llms.txt` asks, it uses `/api/v1` with ETag/If-None-Match, polls no more than once every 60 s, sends an `aihot-api/2.0.0` User-Agent, and syncs retractions and corrections through `selected/snapshot` + `selected/changes`.

The public API has no "first-hand only" filter; the website uses `/api/site/timeline?channel=firstParty`. So that I don't depend on an internal endpoint, could you either:

1. add `channel=firstParty` to `/api/v1/items` (matching the website's "first-hand only"), or
2. return `firstParty: true/false` in `Item.source` (`StoryReport.source` already has this field)?

Also, if `Item` included `group` (`factId`, `additionalSourceCount`) and `tags`, clients could merge several reports of the same event the way the website does.

Thank you! This project lets me keep up with AI news without scrolling social media.
