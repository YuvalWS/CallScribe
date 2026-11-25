# Plan: easier provider setup (status: §1, §3 built; §2 built for Gemini and OpenRouter; §4 built)

Today the user creates a RunPod endpoint by hand, copies its ID, creates an API key, and types model names.
That's the hardest part of onboarding. This plan makes it mostly automatic without adding a CallScribe server.

## 1. RunPod: create the endpoint for the user

**Built** (`providers/ivrit/RunPodSetup.kt`, "Set up automatically" in the RunPod section). The user pastes only a
RunPod API key; CallScribe reuses an endpoint running ivrit.ai's image (preferring one named `callscribe-ivrit`) or
creates one, saves its ID and runs the health check. Verified: the list call finds an existing endpoint made by
ivrit.ai's generator. Not yet verified live: creating a new endpoint (needs an account without one).

Management API (checked against the docs and a live read): `GET/POST https://api.runpod.io/v2/serverless`,
`Authorization: Bearer <key>`. New endpoint = ivrit.ai generator's settings: image
`yairlifshitz/whisper-runpod-serverless:ivrit-2026.05.31`, `type QUEUE`, GPU pools ADA_24/AMPERE_24/AMPERE_48/AMPERE_16,
workers 0–2, idle 5 s, FlashBoot, 50 GB disk, 15 min timeout.

Original idea, for reference:

- RunPod has an API for this (GraphQL `saveTemplate` / `saveEndpoint`, and the newer REST API
  `POST /v1/templates`, `POST /v1/endpoints`). Same key, same account; the user pays RunPod as today.
- Flow: "Set it up for me" → check the key (`/user` or list endpoints) → look for an existing endpoint whose template
  is the ivrit.ai image (reuse it, don't create duplicates) → otherwise create template (ivrit.ai's published
  `runpod-serverless` image, pinned tag) + endpoint with **min workers 0**, max workers 1–3, idle timeout ~5 s,
  a cheap 16–24 GB GPU list → save the endpoint ID → run Test connection.
- Name it `callscribe-ivrit` so the user recognises it in the RunPod console.
- "Advanced" keeps today's manual fields (own endpoint ID, model).
- Phase 0 (before building): confirm the API calls and the image name against RunPod's docs and the ivrit.ai repo on a
  throw-away endpoint; confirm a new endpoint with min workers 0 costs nothing while idle.

**API key.** CallScribe can't create the key — that needs the user's RunPod login, and must stay theirs. The step shows
a short illustrated guide (RunPod → Settings → API keys → Create, "Restricted" with access to serverless only if
RunPod offers it) and a button that opens that page; the user pastes the key. Link ivrit.ai's own guide as well.

## 2. Choosing models: pre-selected, with an online list

**Built for Gemini and OpenRouter** (`providers/ModelCatalog.kt`, "Choose from list" under the model field, in Settings
and onboarding). The list is fetched only when tapped (free call): Gemini `models.list` with the user's key, filtered to
text `generateContent` models; OpenRouter's public `/api/v1/models`, filtered to `structured_outputs`, cheapest first
with "≈ $ / call" (2,500 tokens in, 300 out). The recommended and current models are pinned on top; "Use recommended"
resets. Not done: Claude (keeps its fixed list with prices), ivrit.ai models, "saved model retired" detection.

Original plan:

Hard-coding one model name ages badly; making users type names is error-prone. Do both:

- **Default pre-selected** — a known-good model per provider shipped with the app (today's defaults), so a new user
  never has to choose.
- **"Choose another model"** opens a list fetched live from the provider with the user's own key:
  OpenRouter `GET /api/v1/models` (filter to models supporting structured outputs; show price per call using our
  token estimate), Gemini `models.list` (filter to `generateContent`), Anthropic `GET /v1/models`. For ivrit.ai, the
  ivrit-ai models on Hugging Face (`*-ct2` for faster-whisper).
- Mark the app's recommendation in the list; keep a free-text field under Advanced.
- If the saved model disappears (provider retired it), Test connection says so and offers the current default.

## 3. When the connection fails during onboarding (done in this change)

- The test result is a large green / red card scrolled into view, with a plain hint for common causes
  (wrong key, wrong endpoint/model, no internet, rate limit).
- A failure never blocks Next: settings are saved and can be fixed later in Settings → Providers; the call-list
  banner keeps saying "transcription not set up" until it works.
- Later: the "Try it on your latest call" card on the existing-calls step is the real end-to-end check.

## 4. RunPod cost: what the API can tell (measured 2026-11-24)

- The job status has `executionTime` / `delayTime` only — no price. The app's per-call cost is executionTime × the
  price setting, which **undercounts**: RunPod bills the worker from start to stop (cold start + execution + 5 s idle).
- Measured on the user's endpoint: a 20 s silent clip, delayTime 9.8 s, executionTime 3.1 s → app formula $0.0006,
  **actual balance drop $0.0067**, which appeared about 5 minutes after the job.
- The GraphQL API (`https://api.runpod.io/graphql`, `myself { clientBalance currentSpendPerHr }`) works with the
  user's restricted key. The REST catalog (`/v2/catalog/gpus`, has `price.serverless`) needs `catalog:read`; billing
  history and `/v2/billing/balance` need `billing:read` — the user's key has neither. GraphQL `gpuTypes` has no
  serverless price field.
- **Built:** (a) each call's cost is billed worker time — execution + start-up (the job's wait when over 3 s, capped at
  30 s) + the 5 s idle timeout — × the price (`IvritRunPodTranscriber.billedMs`); the default price is $0.00034/s (the
  dearest GPU in the default endpoint's list), and a saved 0.00019 (the old default) counts as unset. Bulk estimates fit
  transcription seconds from history and price them the same way. (b) A RunPod bulk run reads the balance at the start
  and 7 min after it ends (`RunPodBalance`, `ChargeCheckWorker`), shows "RunPod charged $X" on the bulk card, and
  scales the price per second by charged / estimated (runs over $0.01, kept within $0.0001–0.002/s).
- Not built: (c) billing history with a `billing:read` key. A single call's real charge isn't measured (other activity
  on the account and the 5-minute delay make one call too small to tell apart).

## Open questions

- D1: Create the endpoint automatically by default, or only behind a "Set it up for me" button? (proposed: button)
- D2: Which GPU list / max workers to default to (cost vs speed in bulk runs)?
