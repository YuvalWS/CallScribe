# CallScribe

A free, open-source **call transcription manager** for Samsung phones. It lists the call recordings
your phone already saves (including calls answered by Samsung's call screening), transcribes them in
Hebrew with [ivrit.ai](https://huggingface.co/ivrit-ai) models, and can ask an AI model (Gemini,
any OpenRouter model, or Claude) for a short summary and a spam / not-spam verdict.

**Bring your own services.** CallScribe has no servers. Audio goes only to the RunPod endpoint you
deploy, and transcripts only to the AI provider account you choose. You pay those providers directly
(typically a fraction of a cent per call). Nothing is hard-coded and nothing is sent anywhere until
you configure it.

## Features
- Call list of every recording in `Recordings/Call`, with Samsung's caller-ID label (Suspected Spam,
  Potential Fraud, …), filters, and per-call + total cost.
- **Search** by name, number (any format) or words in transcripts and summaries.
- **Transcribe** button on every row and on the call page; **Analyze** for the AI summary.
- **Policies** per caller type (spam, fraud, other labels, unknown, private, contacts) and per contact:
  transcribe automatically? summarize? how long to keep the recording? A contact's policy overrides its group.
- **Retention**: recordings past their period are moved to the system trash (recoverable 30 days) after you
  confirm, or silently if you grant *Media management* access. Transcripts are kept by default; tick
  *Also delete transcript & summary* to expire text too (runs daily).
- Long calls are compressed on the phone (16 kHz mono AAC) before upload.
- Recordings are identified by content hash, so renames / restores keep their transcripts.
- Optional webhook export (e.g. to Home Assistant).
- API keys are encrypted with the Android Keystore and excluded from backups.

## Requirements
- Android 13+ Samsung phone with call recording enabled
  (Phone → ⋮ → Settings → Record calls → Auto record calls).
- A [RunPod](https://runpod.io) account (for transcription) and optionally an
  [Anthropic API key](https://console.anthropic.com) (for summaries).

## Setup

### 1. Transcription endpoint (RunPod, ~5 minutes)
1. In RunPod → Serverless → **New Endpoint**, deploy the hub template
   **ivrit-ai/runpod-serverless** (or image `yairlifshitz/whisper-runpod-serverless`).
2. Settings: **Min workers 0** (you pay nothing while idle), max workers 1–2, idle timeout ~5 s,
   container disk ≥ 20 GB, any 16–24 GB GPU.
3. Copy the **Endpoint ID** and create an **API key** (Settings → API Keys).
4. In CallScribe → Settings → Providers, paste both and tap **Test connection** (free).
5. Set the *GPU price per second* to the price of the GPU you chose (used only for cost estimates).

Measured on a real endpoint: a 14-minute call took 14 s of GPU time ≈ **$0.003**; cold starts add
~10–20 s of waiting.

### 2. AI summary (optional) — pick one provider in Settings → Providers
- **Gemini** (aistudio.google.com key). Free tier: ~15 requests/minute, spaced automatically. Default model
  `gemini-3.5-flash-lite`. Note: on the free tier Google may use prompts (your transcripts) to improve its products.
- **OpenRouter** (openrouter.ai key, pay per request; exact cost recorded per call). Default
  `anthropic/claude-haiku-5.5` ≈ $0.0003 per call (measured).
- **Claude** (console.anthropic.com key).

"Test connection" is free for every provider.

### 3. Background reliability (Samsung)
Settings → Battery → Background usage limits → **Never sleeping apps** → add CallScribe.

## Building
Needs JDK 17+ and the Android SDK (platform 37). No Android Studio required.

```bash
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk (~32 MB)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Debug builds include adb hooks for development (see `app/src/debug`).

## Privacy
Recordings stay on the phone unless a call is processed. Then the audio goes to *your* RunPod
endpoint and the transcript to *your* AI provider. No analytics, no crash reporting.
Recording calls you take part in is legal in Israel; check the law where you live.
