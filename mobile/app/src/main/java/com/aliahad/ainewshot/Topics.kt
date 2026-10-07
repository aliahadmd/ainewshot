package com.aliahad.ainewshot

/**
 * AIHOT's topics (https://aihot.news/topics), mapped to the tags AIHOT puts on curated items plus name keywords
 * (some companies are only identifiable by name). Following a topic adds matching *curated* stories to your alerts.
 */
data class Topic(val id: String, val label: String, val group: String, val tags: Set<String>, val keywords: List<String> = emptyList())

object Topics {
    val all = listOf(
        Topic("openai", "OpenAI / ChatGPT", "Companies", setOf("OpenAI"), listOf("openai", "chatgpt", "gpt-", "sora", "codex")),
        Topic("anthropic", "Anthropic / Claude", "Companies", setOf("Anthropic"), listOf("anthropic", "claude")),
        Topic("google", "Google / Gemini", "Companies", setOf("Google", "DeepMind"), listOf("gemini", "deepmind", "gemma", "veo")),
        Topic("deepseek", "DeepSeek", "Companies", setOf("DeepSeek"), listOf("deepseek", "深度求索")),
        Topic("qwen", "Qwen", "Companies", setOf("Qwen", "千问", "阿里"), listOf("qwen", "千问", "通义")),
        Topic("kimi", "Kimi / Moonshot", "Companies", setOf("Kimi", "月之暗面"), listOf("kimi", "moonshot", "月之暗面")),
        Topic("minimax", "MiniMax", "Companies", setOf("MiniMax"), listOf("minimax", "海螺")),
        Topic("zhipu", "Zhipu GLM", "Companies", setOf("智谱"), listOf("glm-", "zhipu", "智谱", "z.ai")),
        Topic("xai", "xAI / Grok", "Companies", setOf("xAI"), listOf("xai", "grok")),
        Topic("meta", "Meta / Llama", "Companies", setOf("Meta"), listOf("llama", "meta ai")),
        Topic("microsoft", "Microsoft / Copilot", "Companies", setOf("Microsoft"), listOf("microsoft", "copilot", "azure")),
        Topic("nvidia", "NVIDIA", "Companies", setOf("NVIDIA", "英伟达"), listOf("nvidia", "英伟达", "cuda")),
        Topic("hugging-face", "Hugging Face", "Companies", setOf("Hugging Face"), listOf("hugging face")),
        Topic("cursor", "Cursor", "Companies", setOf("Cursor"), listOf("cursor")),
        Topic("openrouter", "OpenRouter", "Companies", setOf("OpenRouter"), listOf("openrouter")),
        Topic("agent", "Agents", "Directions", setOf("Agent")),
        Topic("coding", "AI coding", "Directions", setOf("编码")),
        Topic("reasoning", "Reasoning", "Directions", setOf("推理")),
        Topic("multimodal", "Multimodal", "Directions", setOf("多模态")),
        Topic("image-gen", "Image generation", "Directions", setOf("图像生成")),
        Topic("video", "AI video", "Directions", setOf("视频", "视频生成")),
        Topic("voice", "Voice & audio", "Directions", setOf("语音")),
        Topic("embodied", "Robotics", "Directions", setOf("具身智能")),
        Topic("on-device", "On-device AI", "Directions", setOf("端侧")),
        Topic("open-source", "Open source", "Directions", setOf("开源生态", "开源/仓库")),
        Topic("engineering", "Deployment & infra", "Directions", setOf("部署/工程")),
        Topic("data-training", "Data & training", "Directions", setOf("数据/训练")),
        Topic("safety", "Safety & alignment", "Directions", setOf("安全/对齐")),
        Topic("mcp", "MCP & tool use", "Directions", setOf("MCP/工具调用")),
        Topic("model-releases", "Model releases", "Kinds", setOf("模型发布")),
        Topic("product-updates", "Product updates", "Kinds", setOf("产品更新")),
        Topic("papers", "Research papers", "Kinds", setOf("论文/研究")),
        Topic("benchmarks", "Benchmarks", "Kinds", setOf("评测/基准")),
        Topic("tutorials", "Tutorials", "Kinds", setOf("教程/实践")),
        Topic("opinions", "Opinions", "Kinds", setOf("大佬观点")),
        Topic("trends", "Trends", "Kinds", setOf("现象/趋势")),
        Topic("industry", "Industry", "Kinds", setOf("行业动态")),
        Topic("policy", "Policy & regulation", "Kinds", setOf("政策/监管")),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Topics (from the followed set) that this item matches, by AIHOT tag or by name in its title. */
    fun matches(item: NewsItem, followed: Set<String>): List<Topic> {
        if (followed.isEmpty()) return emptyList()
        val titles = (item.title + " " + (item.originalTitle ?: "") + " " + item.zhTitle).lowercase()
        return all.filter { it.id in followed && (item.tags.any { t -> t in it.tags } || it.keywords.any { k -> titles.contains(k) }) }
    }
}
