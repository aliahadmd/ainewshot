/**
 * AI News Hot backend.
 *
 * Every request first passes Cloudflare Access (the user signs in with their email; no API keys in the app).
 * The Worker then re-validates the Access JWT itself, so a misconfigured route can never expose the AI.
 *
 *   GET  /auth      Landing page after sign-in (the app's login screen waits for it).
 *   GET  /v1/me     Who is signed in.
 *   POST /v1/chat   Translation, summaries, podcast scripts: Workers AI (env.MODEL, gpt-oss-20b).
 *   POST /v1/mimo   Xiaomi MiMo text-to-speech, proxied with the server-held key (streams through).
 */
import { createRemoteJWKSet, jwtVerify } from "jose";

const MIMO_URL = "https://api.xiaomimimo.com/v1/chat/completions";
const MAX_INPUT_CHARS = 400_000;
// Translation and summaries don't need step-by-step reasoning. gpt-oss can't switch it off; "low" keeps it to a
// sentence or two. (Other families differ: Qwen 3.8 takes enable_thinking:false, DeepSeek V4 reasoning_effort "none".)
const REASONING = { reasoning_effort: "low" } as const;

// Access public keys, cached per isolate (jose refetches when a new key id appears). Not request state.
const jwksByTeam = new Map<string, ReturnType<typeof createRemoteJWKSet>>();

type Identity = { email: string; exp?: number };

function json(body: unknown, status = 200): Response {
	return Response.json(body, { status, headers: { "cache-control": "no-store" } });
}

function log(event: string, fields: Record<string, unknown>) {
	console.log(JSON.stringify({ event, ...fields }));
}

async function authenticate(request: Request, env: Env): Promise<Identity | Response> {
	const url = new URL(request.url);
	// Local `wrangler dev` only (never set in production): .dev.vars DEV_EMAIL on localhost skips Access.
	if (env.DEV_EMAIL && (url.hostname === "localhost" || url.hostname === "127.0.0.1")) return { email: env.DEV_EMAIL };

	if (!env.TEAM_DOMAIN || !env.POLICY_AUD) return json({ error: "not_configured" }, 503);   // fail closed
	const token = request.headers.get("cf-access-jwt-assertion");
	if (!token) return json({ error: "unauthenticated" }, 401);
	let jwks = jwksByTeam.get(env.TEAM_DOMAIN);
	if (!jwks) {
		jwks = createRemoteJWKSet(new URL(`${env.TEAM_DOMAIN}/cdn-cgi/access/certs`));
		jwksByTeam.set(env.TEAM_DOMAIN, jwks);
	}
	try {
		const { payload } = await jwtVerify(token, jwks, { issuer: env.TEAM_DOMAIN, audience: env.POLICY_AUD });
		const email = typeof payload.email === "string" ? payload.email.toLowerCase() : "";
		const allowed = env.ALLOWED_EMAILS.split(",").map((e) => e.trim().toLowerCase()).filter(Boolean);
		if (!email || (allowed.length > 0 && !allowed.includes(email))) return json({ error: "forbidden" }, 403);
		return { email, exp: payload.exp };
	} catch (e) {
		log("auth_failed", { reason: e instanceof Error ? e.message : String(e) });
		return json({ error: "unauthenticated" }, 401);
	}
}

type ChatRequest = { system?: string; user: string; json?: boolean; maxTokens?: number; temperature?: number; stream?: boolean };

async function chat(request: Request, env: Env, who: Identity): Promise<Response> {
	let body: ChatRequest;
	try {
		body = await request.json<ChatRequest>();
	} catch {
		return json({ error: "bad_json" }, 400);
	}
	if (typeof body.user !== "string" || !body.user) return json({ error: "missing_user" }, 400);
	if ((body.system?.length ?? 0) + body.user.length > MAX_INPUT_CHARS) return json({ error: "too_long" }, 413);

	const started = Date.now();
	const messages = [
		...(body.system ? [{ role: "system" as const, content: body.system }] : []),
		{ role: "user" as const, content: body.user },
	];
	const maxTokens = Math.min(Math.max(Math.trunc(body.maxTokens ?? 4000), 1), 16_000);
	if (body.stream && !body.json) {
		// Long texts (articles): stream tokens so the reader sees text within a second or two.
		try {
			const stream = await env.AI.run(env.MODEL, {
				messages,
				max_completion_tokens: maxTokens,
				temperature: body.temperature ?? 0.2,
				...REASONING,
				stream: true,
			});
			log("chat_stream", { email: who.email, ms: Date.now() - started });
			return new Response(stream as ReadableStream, { headers: { "content-type": "text/event-stream", "cache-control": "no-store" } });
		} catch (e) {
			const message = e instanceof Error ? e.message : String(e);
			log("chat_failed", { email: who.email, ms: Date.now() - started, message, stream: true });
			return json({ error: "ai_failed", message }, 502);
		}
	}
	try {
		const out = await env.AI.run(env.MODEL, {
			messages,
			max_completion_tokens: maxTokens,
			temperature: body.temperature ?? 0.2,
			...REASONING,
			...(body.json ? { response_format: { type: "json_object" as const } } : {}),
		});
		const choice = "choices" in out ? out.choices?.[0] : undefined;
		let content = choice?.message?.content ?? "";
		// gpt-oss writes some hyphens and spaces as typographic variants ("Wi‑Fi"); keep plain text.
		content = content.replace(/[\u2010\u2011]/g, "-").replace(/[\u00a0\u202f]/g, " ");
		content = content.replace(/<think>[\s\S]*?<\/think>/g, "").trim();
		if (body.json) content = content.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
		const usage = "usage" in out ? out.usage : undefined;
		log("chat", { email: who.email, ms: Date.now() - started, json: !!body.json, usage });
		if (!content) return json({ error: "empty_response" }, 502);
		return json({ content, usage: usage ?? null });
	} catch (e) {
		const message = e instanceof Error ? e.message : String(e);
		log("chat_failed", { email: who.email, ms: Date.now() - started, message });
		return json({ error: "ai_failed", message }, 502);
	}
}

async function mimo(request: Request, env: Env, who: Identity): Promise<Response> {
	if (!env.MIMO_KEY) return json({ error: "mimo_not_configured" }, 503);
	const upstream = await fetch(MIMO_URL, {
		method: "POST",
		headers: { authorization: `Bearer ${env.MIMO_KEY}`, "content-type": "application/json" },
		body: request.body,   // streamed through, not buffered
	});
	log("mimo", { email: who.email, status: upstream.status });
	return new Response(upstream.body, {
		status: upstream.status,
		headers: {
			"content-type": upstream.headers.get("content-type") ?? "application/octet-stream",
			"cache-control": "no-store",
		},
	});
}

function signedInPage(email: string): Response {
	const safe = email.replace(/[<>&"]/g, "");
	return new Response(
		`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Signed in</title>
<style>body{font:16px system-ui;background:#faf9f6;color:#202a30;display:grid;place-items:center;min-height:90vh;margin:0}
main{text-align:center;padding:24px}h1{font-size:22px;color:#176b75}</style>
<main><h1>✓ Signed in</h1><p>${safe}</p><p>Returning to AI News Hot…</p></main>`,
		{ headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } },
	);
}

export default {
	async fetch(request, env): Promise<Response> {
		const url = new URL(request.url);
		const who = await authenticate(request, env);
		if (who instanceof Response) return who;

		const { success } = await env.LIMITER.limit({ key: who.email });
		if (!success) return json({ error: "rate_limited" }, 429);

		if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/auth")) return signedInPage(who.email);
		if (request.method === "GET" && url.pathname === "/v1/me") return json({ email: who.email, exp: who.exp ?? null, model: env.MODEL });
		if (request.method === "POST" && url.pathname === "/v1/chat") return chat(request, env, who);
		if (request.method === "POST" && url.pathname === "/v1/mimo") return mimo(request, env, who);
		return json({ error: "not_found" }, 404);
	},
} satisfies ExportedHandler<Env>;
