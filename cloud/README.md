# AI News Hot backend (Cloudflare Worker)

The Android app has **no API keys**. You sign in with your email; everything AI goes through this Worker:

| Route | What it does |
|---|---|
| `GET /auth` | "Signed in" page. The app's sign-in screen waits for it, then keeps the Access token. |
| `GET /v1/me` | Who is signed in. |
| `POST /v1/chat` | Translation, summaries, Explain and podcast scripts on **Workers AI**, `@cf/openai/gpt-oss-20b` (`reasoning_effort: low`). `stream: true` streams tokens (used for articles and Explain). |
| `POST /v1/mimo` | Xiaomi MiMo text-to-speech, proxied with the `MIMO_KEY` secret; audio streams straight through. |

## Security
- **Cloudflare Access** guards the `workers.dev` hostname: sign in with a one-time PIN sent to your email. Use **One-time PIN**, because Google sign-in doesn't work inside the app's sign-in screen.
- The Worker **re-validates the Access JWT** (`Cf-Access-Jwt-Assertion`: issuer `TEAM_DOMAIN`, audience `POLICY_AUD`) and checks `ALLOWED_EMAILS`. With those unset it refuses everything (`503 not_configured`).
- A per-user rate limit (240 requests/min) is a backstop against a runaway loop spending AI credits.
- Secrets live only in the Worker (`wrangler secret put MIMO_KEY`). Workers Logs and Traces are on.

## Deploy
```bash
npm install
npx wrangler deploy
npx wrangler secret put MIMO_KEY
```
Then: dashboard › **Workers & Pages › ainewshot-ai › Settings › Domains & Routes › workers.dev › Enable Cloudflare Access**. In the Access application, allow your email, use **One-time PIN**, and set the session to **1 month**. Put the team domain and the application's AUD tag in `wrangler.jsonc` (`TEAM_DOMAIN`, `POLICY_AUD`) and deploy again.

## Local development
`.dev.vars` (git-ignored) with `DEV_EMAIL=you@example.com` and `MIMO_KEY=…`, then `npx wrangler dev`. `DEV_EMAIL` only works on localhost.

## Cost
gpt-oss-20b costs $0.20 per million input tokens and $0.30 per million output tokens, about 130 tokens a second on this workload: a 2,000-character article piece takes about 7 s and costs about $0.0005. Normal daily use is roughly 8,000–12,000 neurons, around the 10,000 a day Workers AI includes for free; the Workers Paid plan covers anything beyond that.

Benchmarked on the app's own prompts (5-story JSON batch and an article piece), reasoning off or lowest: gpt-oss-20b 120–140 tokens/s; Qwen3-30B-A3B ~105 (headlines run long); DeepSeek V4 Flash ~60; Qwen 3.8 27B ~35; GLM 4.7 Flash ~55 (mistranslated a product name); gpt-oss-120b sometimes reasoned past its token limit.
