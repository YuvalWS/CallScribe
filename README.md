# CallScribe

A free, open-source **call transcription manager** for Samsung phones. It lists the call recordings
your phone already saves (including calls answered by Samsung's call screening), transcribes them in
Hebrew with [ivrit.ai](https://huggingface.co/ivrit-ai) models, and can ask Claude for a short summary
and a spam / not-spam verdict.

**Bring your own services.** CallScribe has no servers. Audio goes only to the RunPod endpoint you
deploy, and transcripts only to your own Anthropic account. You pay those providers directly
(typically a fraction of a cent per call). Nothing is hard-coded and nothing is sent anywhere until
you configure it.

## Features
- Call list of every recording in `Recordings/Call`, with Samsung's caller-ID label (Suspected Spam,
  Potential Fraud, …), filters, and per-call + total cost.
- **Transcribe** / **Analyze** buttons on any call.
- **Automatic processing** of new calls: off, all, or by caller type (spam, fraud, unknown, private,
  contacts), plus *Always process* / *Never process* contact lists (the exclude list always wins).
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

### 2. AI summary (optional)
Paste an Anthropic API key in Settings → Providers and pick a model. Haiku is cheapest
(≈ $0.001 per call); Opus is the most capable (≈ $0.01–0.02 per call).

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
