package com.aliahad.ainewshot

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The app's private backend: a Cloudflare Worker behind Cloudflare Access (see /cloud).
 *
 * No API keys live in the app. You sign in once with your email (Access one-time PIN); Access sets its
 * CF_Authorization token on the Worker's domain, we keep that token and send it with every AI / voice call.
 * The Worker runs Workers AI (gpt-oss-20b) and proxies MiMo text-to-speech with a key only it holds.
 * When the session ends (Access session length, e.g. one month), calls are refused and we ask you to sign in again.
 */
object Cloud {
    const val BASE = "https://ainewshot-ai.aliahad.workers.dev"
    private const val COOKIE = "CF_Authorization"

    data class Account(val email: String, val expires: Long)

    /** Null when signed out. */
    val account = MutableStateFlow<Account?>(null)
    @Volatile private var token: String? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        prefs = p
        p.getString("token", null)?.let { t -> parse(t)?.takeIf { it.expires > System.currentTimeMillis() }?.let { token = t; account.value = it } }
    }

    fun signedIn(): Boolean {
        val a = account.value ?: return false
        if (a.expires <= System.currentTimeMillis()) { expire(null); return false }
        return true
    }

    /** Email + expiry from the Access JWT (no verification needed here; the Worker verifies it). */
    private fun parse(jwt: String): Account? = runCatching {
        val payload = JSONObject(String(Base64.decode(jwt.split(".")[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        Account(payload.optString("email"), payload.optLong("exp") * 1000)
    }.getOrNull()

    /** Called by the sign-in screen with the cookie Access set on our domain. */
    fun onSignedIn(ctx: Context, jwt: String): Boolean {
        val a = parse(jwt) ?: return false
        token = jwt; account.value = a
        prefs?.edit()?.putString("token", jwt)?.apply()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFY_ID)
        Log.i(TAG, "signed in as ${a.email} until ${java.util.Date(a.expires)}")
        return true
    }

    fun signOut() {
        token = null; account.value = null
        prefs?.edit()?.remove("token")?.apply()
        CookieManager.getInstance().removeAllCookies(null)
    }

    private const val NOTIFY_ID = 7301
    @Volatile private var notified = false

    /** Session over: forget the token and, once, tell the user (AI and voice fall back until they sign in). */
    private fun expire(ctx: Context?) {
        token = null; account.value = null
        prefs?.edit()?.remove("token")?.apply()
        if (ctx == null || notified) return
        notified = true
        val open = PendingIntent.getActivity(ctx, 41, Intent(ctx, LoginActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, Alerts.CH_ALERTS_QUIET)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("Sign in to keep AI News Hot in English")
            .setContentText("Your Cloudflare sign-in expired. Translations and the natural voice are paused until you sign in.")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFY_ID, n) }
    }

    private fun headers(): Map<String, String> {
        val t = token ?: throw HttpError(401, "Sign in (Me › Settings › Account)")
        return mapOf("Cookie" to "$COOKIE=$t")
    }

    private var appCtx: Context? = null
    fun attach(ctx: Context) { appCtx = ctx.applicationContext; init(ctx) }

    /** Access answers an expired/missing session with a redirect to its login page; the Worker with 401/403. */
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: HttpError) {
        if (e.code in 300..399 || e.code == 401 || e.code == 403) expire(appCtx)
        throw e
    }

    /** One completion from Workers AI. Long outputs (podcast scripts) can take a minute or two. */
    suspend fun chat(system: String, user: String, json: Boolean, maxTokens: Int): String = guarded {
        val body = JSONObject().put("system", system).put("user", user).put("json", json).put("maxTokens", maxTokens)
        val r = Net.postJson("$BASE/v1/chat", headers(), body, timeoutMs = 600_000)
        r.optString("content").takeIf { it.isNotBlank() } ?: throw HttpError(502, "AI returned no content")
    }

    /** Streamed completion: [onText] gets the text so far as it arrives; returns the full text. */
    suspend fun chatStream(system: String, user: String, maxTokens: Int, onText: (String) -> Unit): String = guarded {
        val body = JSONObject().put("system", system).put("user", user).put("maxTokens", maxTokens).put("stream", true)
        val sb = StringBuilder()
        Net.postStream("$BASE/v1/chat", headers(), body, timeoutMs = 300_000) { payload ->
            val o = runCatching { JSONObject(payload) }.getOrNull() ?: return@postStream
            val delta = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")
                ?: o.optString("response")
            // gpt-oss writes some hyphens and spaces as typographic variants ("Wi‑Fi"); keep plain text.
            if (!delta.isNullOrEmpty()) { sb.append(delta.replace(Regex("[\u2010\u2011]"), "-").replace(Regex("[\u00a0\u202f]"), " ")); onText(sb.toString()) }
        }
        sb.toString().trim().ifBlank { throw HttpError(502, "AI returned no content") }
    }

    /** MiMo text-to-speech through the Worker (it adds the key). Same streaming body as MiMo's own API. */
    suspend fun mimo(body: JSONObject, onData: (String) -> Unit) = guarded {
        Net.postStream("$BASE/v1/mimo", headers(), body, onData = onData)
    }

    /** Settings › Account › Test. */
    suspend fun test(ctx: Context): Pair<String, String> {
        val ai = runCatching { chat("Reply with the single word OK.", "ping", json = false, maxTokens = 5); "✓ Working" }
            .getOrElse { "✗ " + (it.message?.take(80) ?: "failed") }
        val voice = runCatching { if (MiMo.pcm(ctx, "Hi.", "Calm.", "Mia").isNotEmpty()) "✓ Working" else "No audio" }
            .getOrElse { "✗ " + (it.message?.take(80) ?: "failed") }
        return ai to voice
    }
}

/**
 * Sign-in: the Worker's page, behind Cloudflare Access. You enter your email, Access emails a one-time PIN,
 * and once the Worker's "Signed in" page loads we take the CF_Authorization cookie Access set and close.
 */
class LoginActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cloud.attach(this)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                if (!url.startsWith(Cloud.BASE)) return
                val jwt = CookieManager.getInstance().getCookie(Cloud.BASE)?.split(";")?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("CF_Authorization=") }?.substringAfter("=")
                if (jwt != null && Cloud.onSignedIn(this@LoginActivity, jwt)) {
                    NewsRepo.appScope.launch { runCatching { NewsRepo.check(applicationContext, force = true) } }
                    view.postDelayed({ finish() }, 900)
                }
            }
        }
        setContentView(web)
        web.loadUrl("${Cloud.BASE}/auth")
    }
}
