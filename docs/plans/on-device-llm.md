# On-device AI analyzer (plan — not implemented)

An optional **"On this phone"** analyzer: a small LLM on the phone reads the Hebrew transcript and returns the same
`Analysis` as Gemini / OpenRouter / Claude (summary, spam verdict, category, intent, action items, relationship tag).
It costs nothing and nothing leaves the phone. Together with on-device transcription
([on-device-transcription.md](on-device-transcription.md)) it makes a fully local pipeline.

Status: planning only (2026-11-23). Labels: **[measured]** = we measured it, **[source]** = published (linked),
**[estimate]** = our extrapolation, which Phase 0 must replace with measurements.

Test phone: Galaxy S20 FE 5G (SM-G781B, Snapdragon 865, **7.8 GB RAM**, ~3.5 GB available with apps running,
Android 13 / One UI 5.1) [measured with `adb shell`]. This is the floor we design for.

---

## TL;DR — recommendation

**Go, behind an evaluation gate (Phase 0).** Runtime: **LiteRT-LM** (Google's Kotlin API, prebuilt AAR on Google
Maven, Apache-2.0, **JSON-schema constrained decoding**). Default model: **Gemma 4 E2B-it** (`.litertlm`, 2.59 GB,
Apache-2.0). Compare it in Phase 0 with **Qwen3.5-2B** (2.12 GB) and, for phones with ≥12 GB RAM, **Gemma 4 E4B**.
Gemini Nano (AICore) is **no-go**: it is not on our phone, it is blocked in the background and Hebrew is not
documented.

Expected on the S20 FE [estimate]: a 5-minute call is analyzed in about **1–1.5 min** on the CPU, using ~0.4 % battery,
~1.7 GB RAM at peak, a **2.6 GB** one-time download and **+23 MB** of APK.

Biggest risk: **Hebrew summary quality of a 2B model.** Dicta's own benchmark shows small models far behind 12B
models on Hebrew summarization (§3.2). The verdict and relationship tag (closed choices) are probably fine. The
summary may be weak. Phase 0 decides this on real calls before any app code is written.

---

## 1. Runtimes on Android

| Runtime | Android integration | JSON / grammar | Models | Verdict |
|---|---|---|---|---|
| **LiteRT-LM** ([overview](https://developers.google.com/edge/litert-lm/overview), [Android guide](https://developers.google.com/edge/litert-lm/android), [GitHub](https://github.com/google-ai-edge/LiteRT-LM), Apache-2.0, ~6.9k★, pushed today) | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` on **Google Maven** (released 2026-11-05) [source: maven-metadata.xml]. AAR is 21 MB. It holds one `jni/arm64-v8a/liblitertlm_jni.so` of **22.7 MB** uncompressed [measured: `unzip -l`], minSdk 24. Depends on gson, kotlin-reflect 2.4.0 and coroutines 1.11 (we are on Kotlin 2.4.21, so this is compatible). **No NDK needed.** CPU (`Backend.CPU(threadCount)`), GPU and NPU backends. | **Yes, verified in source.** `ConversationConfig(enableResponseFormat = true)` + `conversation.sendMessage(…, responseFormat = ResponseFormat.json(schemaJson))`. Also `ResponseFormat.regex(...)` ([ResponseFormat.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt), added 2026-07-16 "Add response_format API for constrained decoding (JSON Schema and Regex) in Kotlin"). Also `maxOutputToken`, `SamplerConfig`, `ThinkingConfig(enableThinking=false)`, `maxNumTokens` (context). | `.litertlm` files on HF `litert-community`: Gemma 4 E2B/E4B, Gemma 3 1B, **Qwen3.5 0.8B/2B/4B**, Qwen3 0.6B–8B, Qwen2.5… | **Chosen.** |
| MediaPipe LLM Inference API | `tasks-genai` | Limited | Same `.task`/`.litertlm` | Android version is **maintenance-only / deprecated; migrate to LiteRT-LM** [source: [LLM Inference guide for Android](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)]. |
| llama.cpp via JNI | No official AAR. Options: build it ourselves with the **NDK + CMake** (the build PC has no room for the NDK; see on-device-transcription §4), or single-maintainer AARs (`dev.ffmpegkit-maintained:llama-android`, `kmp-ai`, `kotlinllamacpp`) | **GBNF grammars**, and converting a JSON Schema subset to GBNF ([grammars/README](https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md)) | Largest choice (any GGUF, including third-party **DictaLM-3.0-1.7B** GGUFs) | Fallback only if LiteRT-LM fails Phase 0 or we need a GGUF-only model. Same trade-off we already rejected for whisper.cpp. |
| ONNX Runtime GenAI | Java API is "**preview**", "package publication is pending", build from source ([Java API](https://onnxruntime.ai/docs/genai/api/java.html)). Arm learning path builds an AAR from source. | LLGuidance constrained decoding, documented for Python only ([guide](https://www.mintlify.com/microsoft/onnxruntime-genai/guides/constrained-decoding)) | Phi, Qwen, Gemma ONNX exports | No. Needs a source build, and the Java side is unfinished. (We already ship ONNX Runtime for sherpa-onnx, but GenAI is a separate library.) |
| MLC LLM | TVM compiler toolchain, Rust + NDK to build the Android library ([comparison, arXiv 2604.24636](https://arxiv.org/html/2604.24636v1)) | JSON mode via an OpenAI-style API | Broad (via TVM); some community MLC builds of DictaLM exist | No. The toolchain is too heavy for this project. |
| **Android AICore / Gemini Nano** (ML Kit GenAI Prompt API) | `com.google.mlkit:genai-prompt`, **beta** ([Prompt API](https://developers.google.com/ml-kit/genai/prompt/android)) | "typed structured output" | Gemini Nano only (system model) | **No-go**: see §1.1. |

### 1.1 Gemini Nano / Galaxy AI for third-party apps (Samsung)
- **Devices** ([ML Kit GenAI overview](https://developers.google.com/ml-kit/genai), fetched 2026-11-23): on Samsung,
  Prompt API / custom generation is listed only for Galaxy **S25, S25+, S25 Edge, S25 Ultra, S26 family (incl. S26 FE),
  Tab S12+/Ultra, Z Flip7/Flip8, Z Fold7/Fold8/Fold8 Ultra, Z TriFold** (Gemini Nano 4). **No S20–S24, S24 FE or
  A-series.** "Availability… may vary depending on the device's RAM configuration, OS version, and AICore update status."
- **Background use is blocked**: "GenAI API inference is permitted only when the app is the top foreground
  application… including using a foreground service, will result in `ErrorCode.BACKGROUND_USE_BLOCKED`." Our
  pipeline runs in WorkManager after a call ends, so this rules it out for automatic analysis.
- Per-app quotas (`BUSY`, `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`), no numbers published.
- **Languages**: the Summarization API supports English, Japanese and Korean only. The Prompt API page has no
  language list and **Hebrew is not mentioned anywhere**.
- **Galaxy AI itself** has no public SDK for its on-device LLM. Samsung says its Personal Data Engine "is exclusive to
  Samsung native apps" ([One UI 8 rollout](https://news.samsung.com/uk/samsung-begins-official-rollout-of-one-ui-8-to-galaxy-devices)).
  Exynos AI Studio is a chip-vendor toolchain, not an app API
  ([Samsung Semiconductor blog](https://semiconductor.samsung.com/news-events/tech-blog/unpacking-samsungs-comprehensive-on-device-ai-sdk-toolchain-strategy)).
  **One UI 9:** we found no announcement that opens Galaxy AI models to third parties. The only third-party path on
  Galaxy is Gemini Nano through ML Kit, as above. Re-check when One UI 9 ships. The S20 FE (One UI 5.1) will not get it.
- Possible later add-on (not planned): on a supported phone, a manual **Analyze** tap with the app open could use
  Gemini Nano. It would need its own Hebrew evaluation, so it is out of scope for now.

---

## 2. Candidate models

Sizes are the exact `.litertlm` files on Hugging Face [source: HF API tree, 2026-11-23]. Speed figures are LiteRT-LM's
own benchmark (1024 prefill + 256 decode tokens, CPU with 4 threads) on a **Galaxy S26 Ultra**
([E2B card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), [E4B card](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)).

| Model | File | Licence | Hebrew | S26 Ultra CPU prefill / decode tok/s, peak RAM | S865 estimate (CPU) | Notes |
|---|---|---|---|---|---|---|
| **Gemma 4 E2B-it** | `gemma-4-E2B-it.litertlm` **2,588,147,712 B**, sha256 `181938105e0e…b97139a63c` @ repo rev `2e12bfd8` | **Apache-2.0** ([Google OSS blog, 2026-04-02](https://opensource.googleblog.com/2026/03/gemma-4-expanding-the-gemmaverse-with-apache-20.html)) | Pre-trained on 140+ languages, "out-of-the-box" support for 35+. Hebrew is not named. 262k vocabulary (Gemma tokenizer). **Unmeasured → Phase 0.** | **557 / 46.9**, 1.73 GB (GPU 3,808 / 52.1, 0.68 GB) | prefill ~80–150, decode ~8–15 [estimate] | Mixed 2/4/8-bit QAT. 32k context. Text-only use loads ~0.8 GB of weights. MTP speculative decoding (up to 1.5× on CPU). Not gated. |
| Gemma 4 E4B-it | `gemma-4-E4B-it.litertlm` 3,659,530,240 B, sha256 `0b2a8980ce15…145bd52e0` | Apache-2.0 | as above, likely better | **195 / 17.7**, **3.28 GB** (GPU 1,293 / 22.1) | prefill ~30–50, decode ~3–6 [estimate] | Peak RAM ≈ all free RAM on an 8 GB phone, so there is a risk of being killed. Offer only on phones with ≥12 GB RAM, or with GPU. |
| **Qwen3.5-2B** | `Qwen3.5-2B_int8.litertlm` 2,119,843,840 B | Apache-2.0 ([card](https://huggingface.co/Qwen/Qwen3.5-2B)) | "201 languages and dialects" ([0.8B card](https://huggingface.co/Qwen/Qwen3.5-0.8B)). Qwen3-4B-Base scores above Gemma-3-4B on the Hebrew leaderboard (cached snippet, unverified; [leaderboard](https://huggingface.co/blog/leaderboard-hebrew)) | not published | similar to E2B [estimate] | Released 2026-03. Thinking model: turn thinking off (`ThinkingConfig(enableThinking=false)`). |
| Qwen3.5-0.8B | `…_mixed48_h1024.litertlm` 487 MB / `_int8` 965 MB | Apache-2.0 | weak [estimate] | — | fast | "Lite" fallback for low-RAM phones. Expect a poor summary. Measure anyway. |
| **DictaLM-3.0-1.7B-Instruct** | no `.litertlm`. Third-party GGUF / MLC only (Q4_K_M, Q8_0) | Apache-2.0 ([card](https://huggingface.co/dicta-il/DictaLM-3.0-1.7B-Instruct)) | **Hebrew-tuned** (Qwen3-1.7B + 100B Hebrew tokens). Hebrew base average **51.5 vs 43.3** for Qwen3-1.7B. Chat summarization win-rate vs Gemini-2.5-Pro **9.72**, against **39.48 for gemma-3-12b-it** ([paper, Tables 3 & 8](https://arxiv.org/html/2602.02104v1)) | — | ~E2B [estimate] | Needs conversion to `.litertlm` (Qwen3 architecture is supported by the converter) or llama.cpp. Stretch candidate: only if Gemma/Qwen fail Hebrew. |
| Gemma 3 1B / 270M | `.litertlm` exists | Gemma licence | gemma-3-1b-it summarization win-rate ≈ 0.35 in the same table (from a model-generated summary of the paper, unverified) | — | — | **Rejected**: too weak in Hebrew. |
| Phi-4-mini (3.8B) | ONNX / GGUF | MIT | No Hebrew evidence found | — | — | Not considered: larger than E2B and no Hebrew evidence. |

**What the Dicta numbers mean for us:** a 1.7B Hebrew-tuned chat model wins only ~10 % of summaries against a
frontier model, so small models write clearly weaker Hebrew summaries. Our need is smaller: a 2–4 sentence gist of a phone call, not
a polished article. The decision comes from Phase 0 on our own calls (§6), not from leaderboards.

### 2.1 How long does a call take? [estimate, to be measured]
- Hebrew conversation is ~110–150 words/min for both speakers together, so a **2–5 min call ≈ 250–750 words**.
- Tokens: Gemma's 262k vocabulary covers Hebrew far better than old tokenizers (Mistral needed **5.81 tokens/Hebrew word**,
  [DictaLM 2.0 paper](https://arxiv.org/pdf/2407.07080)). We assume **1.5–2.5 tokens/word** and Phase 0 measures it.
  Transcript ≈ 400–1,900 tokens. The system prompt (English, ~2,400 chars) is ≈ 600 tokens. Metadata ≈ 80 tokens.
  **Input ≈ 1,100–2,600 tokens.**
- Output: summary (2–4 sentences ≈ 100–200 tokens) + intent + action items + keys ≈ **250–400 tokens**.
- **Gemma 4 E2B on the S865 CPU**: prefill 2,600 / ~100 tok/s ≈ 26 s, decode 350 / ~10 tok/s ≈ 35 s, engine load
  ~5–15 s → **≈ 1–1.5 min for a 5-min call**, ≈ 30–50 s for a 2-min call.
- E4B: ≈ 3–4 min for a 5-min call. GPU (Adreno 650): unknown. LiteRT-LM GPU on this 2020 GPU may or may not
  work, so Phase 0 tries it.
- Decode dominates. Keeping the output short (`maxOutputToken = 512`, at most 3 action items) matters more than the prompt length.

### 2.2 Battery, heat, RAM [estimate]
- CPU at ~3–4 W for ~70 s ≈ 250 J ≈ **0.4 %** of the 17 Wh battery per 5-min call (E4B ≈ 1–1.5 %). Compare on-device
  transcription: ≈ 0.4 % for the same call. Both together ≈ 1 % per 5-min call.
- Peak RAM ~1.7 GB (E2B). The ASR recognizer must be released before the LLM loads (it already is: `rec.release()` in
  `finally`). Never hold both.
- Heat: the S865 throttles after a few minutes of full load. Running only while charging (default on) makes this worse
  but is still the right default for battery. Measure throttling in Phase 0 by analyzing 10 calls back to back.

---

## 3. Can a tiny model fill `AnalysisPrompt`'s JSON schema?

### 3.1 Structure: yes, with constrained decoding
`AnalysisPrompt.schemaJson(strict = true)` is a flat object: 5 strings, 1 number, 1 string array and 3 **enums**
(`verdict`, `category`, `relationship`). LiteRT-LM's `ResponseFormat.json(schema)` masks tokens during decoding so the
output always parses and every enum value is legal. Small models without constraints often break JSON (unclosed
strings, invented keys, Hebrew quote marks inside strings), so **constrained decoding is required, not optional.**

Adjustments for a small model (only in the on-device analyzer; the cloud prompt stays as it is):
1. **Field order**: keep `summary_he` and `caller_intent` first, then `category`, `verdict`, `relationship`, then
   `spam_confidence`. The model "reads" the call by summarizing before it classifies. The current `PROPERTIES` order
   (summary → verdict → confidence → category → intent → items → relationship) has the verdict before the intent. Use a
   reordered **copy** of the schema for on-device (`AnalysisPrompt.schemaMap(order = ON_DEVICE)`).
2. **`spam_confidence`**: small models give badly calibrated floats. Either constrain it with a regex-like enum
   (`0.1|0.3|0.5|0.7|0.9`) or ignore the model's value and derive it from the verdict (spam 0.85 / unsure 0.5 /
   not_spam 0.15). Recommended: derive it, because policies already key on the verdict.
3. **Lengths**: `maxLength` on strings (summary ≤ 400 chars, intent ≤ 120, ≤ 3 action items of ≤ 80 chars), if
   LiteRT-LM's schema subset honours it. Otherwise use `maxOutputToken = 512` plus a post-trim.
4. **Compact system prompt** (`AnalysisPrompt.SYSTEM_COMPACT`): the same rules in fewer words, the relationship examples
   kept (they carry the Hebrew cue words), and the instruction "the transcript has no speaker labels". Thinking off.
   Greedy / temperature 0 (`SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)`) for repeatable results.
5. **Long calls**: on-device context is capped (`EngineConfig.maxNumTokens = 4096`). Transcript budget ≈ 3,000 tokens
   ≈ 6,000 Hebrew chars [estimate]. Longer calls use **head 60 % + tail 40 %** with a marker "[…middle of the call
   omitted…]". Calls usually state their purpose at the start and agree on next steps at the end. A map-reduce
   summary over chunks is out of scope; revisit if Phase 0 shows long calls matter.

### 3.2 Quality: the enums are likely fine, the summary is the open question
- Constrained decoding guarantees **format**, not **correctness**. A wrong enum is still a legal enum.
- Classification into 3–5 labels is the easy part for 2B models (expected agreement with Gemini ≥ 80 %, [estimate]).
- The Hebrew summary is the hard part (§2, Dicta Table 8). Failure modes to look for: English or mixed-language output,
  repetition loops (use `RepetitionPenaltyConfig` / `NoRepeatNgramConfig`), copying the transcript verbatim, invented
  details.

### 3.3 Fallbacks (in order)
1. Constrained JSON fails to parse (should not happen) → retry once with greedy decoding and a 2× smaller transcript.
2. Constrained decoding not supported by the chosen model file / LiteRT-LM version → unconstrained + `AnalysisPrompt.parse`
   (already strips ``` fences) + a tolerant repair (cut at the last `}`, fix trailing commas). If that still fails,
   two-step: (a) plain-text Hebrew summary, (b) a tiny JSON with only the enums, constrained by `ResponseFormat.regex`.
3. Engine fails to load (out of memory, unsupported CPU, corrupt file) → `ProviderError(retryable = false)` with a clear
   message ("not enough memory for the on-device AI model; close apps or pick a cloud provider"). The call keeps its
   transcript and status `TRANSCRIBED`; nothing is lost.
4. The model writes a summary that is not Hebrew (check: < 50 % Hebrew letters) → retry once with a stronger system
   line. If it fails again, store it anyway with `category` etc. and mark `analysisJson.lowQuality = true` (UI shows
   nothing special yet).
- No automatic fall back **to the cloud**: that would break the privacy promise. The user picks the cloud explicitly.

---

## 4. Fit with the secrets plan ([secrets-in-recordings.md](secrets-in-recordings.md), D1–D6)

| Decision | Effect on the on-device analyzer |
|---|---|
| D1 — store only masked text | Unchanged. The analyzer reads `transcriptText` from Room, which is already masked. |
| D2 — send the redacted transcript to the AI | Keep doing it for on-device too, even though nothing leaves the phone: the same code path, placeholders like `[קוד אימות]` help the scam verdict, and the model cannot echo a number it never saw into the stored summary. |
| D3 — detector on for every provider | Unchanged; the on-device analyzer is "every provider". |
| D4 — keep Android backup of the DB | Unchanged: the analysis holds masked data only. The **model file** lives in `noBackupFilesDir` (never backed up). |
| D5 / D6 | Unaffected. With on-device ASR **and** on-device AI, the only remaining sinks are notifications, webhook and Drive, which Phase 1/2 of the secrets plan already handle. |

**Note:** choosing on-device transcription + on-device analysis removes threat-model rows 2 (RunPod) and 5 (AI
provider) for that call. This is the strongest privacy setup the app can offer. README and onboarding should say so plainly.

### 4.1 Secret detection: on-device LLM vs regex/heuristics

| | Rules (secrets plan §2.2–2.3) | On-device LLM |
|---|---|---|
| Cards / IDs / IBAN (checksums) | **Very good** (Luhn, ID check digit, mod-97) | Worse: LLMs can't do checksums; it would guess |
| Codes, CVV, bank details near cue words | Good, deterministic | Similar, non-deterministic |
| Passwords spoken as words, spelled English letters, paraphrase ("the code is my dog's name and the year") | **Poor** (cue + next N tokens only) | **Better.** This is the one place an LLM adds recall |
| Exact character offsets for masking | Exact | Poor; small models misquote. We would have to ask for quoted phrases and fuzzy-match them back |
| Speed | milliseconds, any phone | 30–90 s, needs the 2.6 GB model |
| Testable on the JVM / CI | Yes | No (needs the phone or a desktop LiteRT-LM run) |

**Recommendation:** rules stay the gate (secrets plan Phase 1, unchanged). The on-device LLM adds **one extra field**
to its own output: `sensitive_left: "none" | "password" | "code" | "card_or_id" | "bank" | "other"`. This is a
**second opinion on the already-redacted text**, exactly what secrets plan §2.4 allows ("useful only as a second
opinion on a redacted transcript"). It costs nothing extra: same call, ~5 more output tokens. If it is not `none`, set
the 🔒 flag and the notification goes private. It never un-masks anything. It is stored in `analysisJson`, not a new column.
The field is **on-device only**. Adding it to the cloud schema too is a separate small decision; it is harmless there
because the text is already redacted.

### 4.2 What can run before anything leaves the phone
| Step | Before upload to RunPod? | Before the cloud AI? |
|---|---|---|
| Metadata policy (caller is "my bank") | yes | yes |
| On-device ASR → rules (secrets Phase 3 screening pass) | yes | yes |
| On-device ASR → rules → **on-device LLM** "is anything sensitive left?" | yes, but adds 30–90 s per call | yes |
| Cloud ASR → rules | **no** (audio already sent) | yes |
| Cloud ASR → rules → on-device LLM second opinion → *then* decide whether to call the cloud AI | no | **yes.** Possible option: "if the on-device check finds something sensitive, analyze this call on the phone instead of the cloud". This is a cheap way to keep risky calls local while normal calls get cloud quality. **Phase 4, decision D7 for the user.** |

An **unmasked** LLM pass (running the LLM on the raw text in memory before masking, to find secrets rules miss) is
possible only inside `ProcessWorker.transcribe`, before `saveTranscript`. It would add a minute to every transcription
and make masking depend on a 2.6 GB model. **Not planned**, consistent with secrets plan "Not planned: LLM-based
detection as the primary gate".

---

## 5. Integration design

### 5.1 Code
- `providers/ondevice/OnDeviceModel.kt`: add `LLM_GEMMA4_E2B` (and the Phase-0 winners) to `OnDeviceModels` with repo
  `litert-community/gemma-4-E2B-it-litert-lm`, **pinned revision** `2e12bfd869b2962984f97a1f2a26807316d534cc`, file
  `gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes, sha256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`,
  licence Apache-2.0. Add `kind: ASR | LLM` and `minRamBytes` (E2B: 6 GB total RAM; E4B: 12 GB).
- `ModelStore`: today it downloads in an app-scoped coroutine. That is fine for 72 MB, **not for 2.6 GB** (minutes; the
  process can be killed). Changes:
  - **Resume** with HTTP `Range` from the existing `.part`, re-hashing the bytes already on disk first.
  - Run the download as a **`ModelDownloadWorker`** (unique work per model id, `NetworkType.UNMETERED` by default, a
    foreground `dataSync` notification with progress). `ModelStore.state()` follows the WorkManager progress so the UI
    keeps working as it does today.
  - Free-space check already exists (need + 50 MB); raise the margin to need + 500 MB for the big model.
- `providers/ondevice/OnDeviceAnalyzer.kt` implements `CallAnalyzer` (id `ondevice-llm`, displayName "On this phone (Gemma 4 E2B)"):
  - `isConfigured()` = model installed **and** the phone has ≥ `minRamBytes` total RAM.
  - `test()` = load the engine, generate ≤ 8 tokens from a fixed English prompt, close; report load ms + tok/s.
  - `analyze(transcript, meta)` on `Dispatchers.IO`: `Engine(EngineConfig(modelPath, Backend.CPU(threads), maxNumTokens = 4096, cacheDir = context.cacheDir))`
    → `engine.initialize()` → `createConversation(ConversationConfig(systemInstruction = SYSTEM_COMPACT, samplerConfig = greedy, maxOutputToken = 512, enableResponseFormat = true, thinkingConfig = off))`
    → `sendMessage(AnalysisPrompt.user(budgeted(transcript), meta), responseFormat = ResponseFormat.json(onDeviceSchema))`
    → `AnalysisPrompt.parse()` → derive `spam_confidence` → `AnalysisResult(cost = Cost(0.0, "On this phone: 48 s"), inputTokens, outputTokens)`.
    Always `close()` conversation and engine in `finally`. Check `coroutineContext.isActive` between steps; LiteRT-LM has
    `Conversation.cancelProcess()` (verify name) for stopping mid-generation.
  - Also implements a marker interface `LocalAnalyzer` (like `LocalTranscriber`) so the worker knows to go foreground,
    skip the network constraint and honour "only while charging".
- `ProviderRegistry`: `analyzerIds += OnDeviceAnalyzer.ID`, `analyzer()` builds it from `models`, `s.onDeviceThreads`,
  `s.onDeviceLlmModelId`. Add `isAnalyzerOnDevice(s)`.
- `Settings.kt`: `onDeviceLlmModelId` (default E2B), reuse `onDeviceThreads` (default 4 for the LLM, which unlike the
  streaming ASR scales with threads: E2B was benchmarked with 4. Phase 0 checks this; if 2 vs 4 differs, add
  `onDeviceLlmThreads`). Reuse `onDeviceOnlyWhileCharging` and rename its label to "Automatic on-device work only while charging".
- `AnalysisPrompt`: `SYSTEM_COMPACT`, `schemaMap(order, extraProps)` so on-device can reorder and add `sensitive_left`,
  `budgetTranscript(text, maxChars)` (head/tail). Pure, unit-tested.
- `build.gradle.kts`: `implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")`. Google Maven is
  already a repository, so **no custom download like sherpa-onnx**. ProGuard: keep `com.google.ai.edge.litertlm.**`
  (JNI + gson reflection). Check whether the GPU backend needs an extra accelerator `.so` (the AAR has only one lib).

### 5.2 WorkManager and the 10-minute background stop
- Today analysis runs inside the same `ProcessWorker` run as transcription. With both on-device, a 15-min call =
  ~6 min ASR + ~2 min LLM. That fits, but a 25-min call does not.
- **Change**: when the analyzer is local, `ProcessWorker` does **not** analyze inline after a local transcription.
  It enqueues a **second unique work** `analyze-<id>` (steps = `analyze`) when transcription finishes. Each step then
  gets its own 10-minute window and its own retry and back-off. The cloud path is unchanged (inline).
- `analyzeOnDevice()` mirrors `transcribeOnDevice()`: `setForeground(TranscribeProgress…)` (reuse the notification,
  text "Summarizing on the phone…"), on `STOP_REASON_TIMEOUT` / `FOREGROUND_SERVICE_TIMEOUT` mark the call with
  "Android stopped the on-device summary after 10 minutes. Open CallScribe and tap Analyze." Use the same
  `timeoutMarks()` guard against loops. A 5-min call needs ~1.5 min, so a timeout means something is badly wrong
  (throttling or swapping); still handle it.
- `WorkScheduler.enqueueProcess`: `needsNetwork` must treat analyze as local when the analyzer is on-device, and
  `requiresCharging` must also apply to an automatic **local analyze** step. Add
  `setRequiresBatteryNotLow(true)` for local analyze.
- Never run ASR and LLM at the same time: the unique work names serialize per call. Across calls, add a
  `WorkManager` tag `ondevice-heavy` and enqueue local analyses with `ExistingWorkPolicy.APPEND_OR_REPLACE` on a single
  chain `ondevice-llm-queue`, so only one LLM engine (1.7 GB) is ever loaded.
- Manual "Analyze"/"Transcribe" taps run at once (no charging constraint), as today.
- Re-analyze is idempotent: same transcript → same output (greedy).

### 5.3 APK size
Models are never bundled. The engine adds `liblitertlm_jni.so` **22.7 MB** (arm64, stored uncompressed, like sherpa-onnx)
+ ~0.2 MB classes + kotlin-reflect (~3 MB before R8) → release APK **≈ 67 → ~92 MB** [estimate]. The open decision
**on-device-transcription §8.1** (download native libs with the model) applies here too and becomes more attractive
(≈ 56 MB of native code for features most users may not enable). Same trade-off: downloading executable code is
fine for sideloading and not allowed on Play. Don't decide it in this plan; flag it to the user (D9 below).

### 5.4 UI (Settings → Providers → AI analysis)
- Fourth radio: **"On this phone — free and private, slower, simpler summaries"**.
- Section `OnDeviceAiSection` (sibling of `OnDeviceSection.kt`, sharing its download/progress/delete composable,
  generalised to take an `OnDeviceModel`): model name + licence + **"2.6 GB download, Wi-Fi only"**; RAM check message
  if the phone is below `minRamBytes`; Download / Cancel / Retry / Delete; **Test**; threads; the shared charging
  switch; one line "A 5-minute call takes about a minute to summarize on this phone" (fill in measured numbers).
- **Delete-model prompt** when switching the analyzer away from on-device (same pattern as `pickTranscriber` /
  `DeleteModelPrompt`, generalised to a model parameter), and the "downloaded but not in use → Delete" leftover row.
- Call detail: the cost line already shows `Cost.detail` ("On this phone: 48 s").

### 5.5 Onboarding (required by CLAUDE.md, same change as Phase 2)
- `SetupStep.ANALYSIS` stays one step. `AnalyzerProviderFields` (shared with the Providers screen) gets the fourth
  option automatically, so onboarding shows it too. Heading text: "AI summary & spam check" → body adds one line:
  "Or run a small AI on this phone: free and private, a 2.6 GB download."
- `SetupStatus.analyzerConfigured` = analyzer `isConfigured()`: for on-device that means the **model is downloaded**.
  Starting a 2.6 GB download in onboarding must not block Next: the step shows progress and the user can continue.
  The download runs in `ModelDownloadWorker` and survives leaving the screen.
- Welcome / Done privacy line: "With on-device transcription and on-device AI, nothing leaves your phone."
- `SetupStateTest`: a case where the analyzer is on-device but the model is not downloaded (→ ANALYSIS not done), and
  one where it is downloaded (→ done). `analyzerConfigured` is not in `missingRequired()` today; keep it that way.
- `docs/plans/onboarding.md` step 6 row: "Gemini / OpenRouter / Claude / **On this phone**".
- No new runtime permission (the download notification uses the existing `POST_NOTIFICATIONS`; foreground service type
  `dataSync` is already declared for on-device transcription; check that the manifest allows `dataSync` for
  the download worker).

---

## 6. Phased plan (steps detailed enough to implement)

### Phase 0 — evaluation spike (go/no-go), ~1–2 days, **no app code merged**
Goal: real numbers for Hebrew quality, JSON validity and speed on the S20 FE.

0.1 **Desktop prompt iteration** (fast loop, no phone): `pip install litert-lm` (or `uv tool install litert-lm`), then
`litert-lm run --from-huggingface-repo=litert-community/gemma-4-E2B-it-litert-lm gemma-4-E2B-it.litertlm …`. Or use the
JVM artifact of LiteRT-LM from a small Kotlin script in `tools/llm-eval/` (it can reuse `AnalysisPrompt` directly).
Use it to tune `SYSTEM_COMPACT`, field order and `ResponseFormat.json` on synthetic transcripts. The desktop gives
quality, not phone speed.

0.2 **Evaluation set (privacy rules: the repo is public)**:
- **Synthetic fixtures (committed)**: 15–20 hand-written Hebrew transcripts in both ASR styles (Whisper-like with
  punctuation, Zipformer-like with number words). Cover: family chat, colleague about a shift, plumber quote, clinic
  reminder, courier, bank fraud ("give me the code"), telemarketing, robocall, survey, wrong number, a screened call
  with the Samsung assistant, a 1-line hang-up, a long 12-minute call (tests the head/tail budget), and redaction
  placeholders. Each has an expected `verdict` + `relationship` + `category`. Put them in
  `app/src/test/resources/analysis-fixtures/*.json`.
- **Real calls (never committed)**: the phone's DB already holds real calls **analyzed by Gemini/OpenRouter**
  (`analysisJson`). Those are the reference answers, so there is **no new cloud spend**. A debug hook
  `DEBUG_LLM_EVAL` (in `app/src/debug/.../DebugReceiver.kt`) runs the on-device analyzer over the N most recent
  analyzed calls (default 20). It writes `{id, durationSec, transcriptChars, cloud: {...}, local: {...}, ms, inTok, outTok,
  peakRssMb, batteryDelta}` to `getExternalFilesDir("llm-eval")/run-<ts>.jsonl`. **It must not update Room rows,
  must not touch recordings and must not delete anything.** Pull with `adb pull` to a folder outside the repo
  (gitignored `eval-out/`).
- The user reads the summaries side by side (blind A/B order) in a small generated HTML page on the PC, and marks each
  local summary as **good / acceptable / wrong**.

0.3 **Candidates**: Gemma 4 E2B (CPU 4 threads, CPU 2 threads, GPU), Qwen3.5-2B int8 (CPU 4), and Gemma 4 E4B (CPU
4) on 5 calls only, to see whether the RAM risk materialises. Qwen3.5-0.8B on the synthetic set only.

0.4 **Measure** per run: JSON validity (expect 100 % with constraints), agreement with the cloud on `verdict`,
`relationship`, `category`, summary rating, Hebrew-letter ratio, wall time split into load / prefill / decode, tokens per
Hebrew word (from `inTok`), peak RSS, battery % over 10 back-to-back calls (on battery, not USB: the transcription
plan could not measure battery while on USB), and temperature/throttling (`dumpsys thermalservice`).

0.5 **Go/no-go gate** (the user confirms the thresholds):
- JSON valid ≥ 99 %.
- Verdict agreement with the cloud ≥ 85 %, where spam ↔ not_spam swaps count double (≤ 5 % allowed).
- Relationship agreement ≥ 75 %.
- Summary good or acceptable ≥ 70 %, and "wrong" (invents facts) ≤ 10 %.
- 5-min call ≤ 3 min wall time on the S20 FE, peak RSS ≤ 2.5 GB.
- **If only the enums pass**: ship as an "on-device spam check & tags" analyzer with a one-line intent and no summary,
  or ask the user (D8).
- **If nothing passes**: no-go. Write the numbers into this doc and stop.

Deliverable: a results table appended here as §7 (aggregates only, no transcript text), and the chosen model + revision + sha256.

### Phase 1 — engine + analyzer (≈ 1.5 days)
1. Add the LiteRT-LM dependency + ProGuard rules; check the release build and APK size; record the size here.
2. `OnDeviceModel`: `kind`, `minRamBytes`. Add the chosen LLM model spec with pinned revision/size/sha256.
   `OnDeviceModelTest`: URL built from the pinned revision; sha256 is 64 hex chars; LLM models are never in the APK.
3. `ModelStore`: Range-resume + `ModelDownloadWorker` (unmetered, foreground progress). Unit-test the pure parts
   (resume offset, hash of existing bytes, state mapping). Manually test kill-and-resume with the 2.6 GB file.
4. `AnalysisPrompt`: `SYSTEM_COMPACT`, `schemaMap(order, extra)`, `budgetTranscript()`, `confidenceFromVerdict()`,
   Hebrew-ratio check. `AnalysisPromptTest` for each.
5. `OnDeviceAnalyzer` + `LocalAnalyzer` + registry entry + settings fields (`Settings.kt` read/write + defaults).
6. Debug hook `DEBUG_ONDEVICE_LLM` (analyze one call id, log timings, no DB write) for quick checks.

### Phase 2 — pipeline, UI, onboarding (≈ 1.5 days)
1. `ProcessWorker`: `analyzeOnDevice()` (foreground, timeout handling, `timeoutMarks`), and the separate
   `analyze-<id>` work when the analyzer is local. `WorkScheduler.enqueueProcess`: network/charging/battery-not-low
   constraints per §5.2, plus the single-LLM queue.
2. `ProvidersScreen.AnalyzerProviderFields`: fourth option + `OnDeviceAiSection`; generalise `OnDeviceSection`'s
   download/delete composable and `DeleteModelPrompt` / `OnDeviceLeftover` to take a model.
3. Onboarding per §5.5 (`SetupState.kt`, `Onboarding.kt`, `SetupStateTest`, `docs/plans/onboarding.md`).
4. README: "AI on this phone" section (size, speed, quality caveat, privacy: fully local with on-device transcription),
   and the third-party notice for LiteRT-LM (Apache-2.0) and the model licence in `THIRD_PARTY_NOTICES.md`.
5. `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, install with `adb install -r` (keeps data), run the
   S20 FE checks: Test button, one manual Analyze, one automatic call while charging, switching provider → delete prompt.

### Phase 3 — secrets integration (after secrets Phase 1 lands, ≈ ½ day)
1. Add `sensitive_left` to the on-device schema and prompt; map it to the 🔒 flag (`sensitiveKinds` gets `LLM_FLAG`).
2. Test: synthetic fixture with a password spoken as words that the rules miss → flag set, notification private.

### Phase 4 — optional, after real use (decide later)
- D7: "keep risky calls local" (cloud ASR → rules → on-device check → cloud AI only if clean).
- GPU backend by default if Phase 0 showed it works and is faster on Adreno.
- E4B as a "better quality" choice on ≥ 12 GB phones. DictaLM-3.0-1.7B converted to `.litertlm` if Gemma's Hebrew
  is the weak point. Bring-your-own model URL + sha256 (fits "nothing hard-coded").
- Gemini Nano for manual taps on supported Galaxy phones (foreground only), if Hebrew works there.

---

## 7. Phase 0 results
*(to be filled in: aggregates only, no transcript text)*

## 8. Open decisions for the user
- **D7** — "Keep risky calls local": analyze on the phone when the on-device check flags sensitive content? (Phase 4)
- **D8** — If the summary fails the gate but the enums pass: ship spam check + tags only, or nothing?
- **D9** — APK +23 MB for an optional feature: accept, or download the native engines with the models
  (on-device-transcription §8.1) for both ASR and LLM?
- **D10** — Phase 0 gate thresholds (§6, 0.5): accept as written?

## Sources
- LiteRT-LM: [overview](https://developers.google.com/edge/litert-lm/overview) · [Android guide](https://developers.google.com/edge/litert-lm/android) · [GitHub](https://github.com/google-ai-edge/LiteRT-LM) · [ResponseFormat.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt) · [Config.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Config.kt) · [Google Maven metadata](https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml) · [blog: Blazing fast on-device GenAI with LiteRT-LM](https://developers.googleblog.com/en/blazing-fast-on-device-genai-with-litert-lm/)
- Gemma 4: [LiteRT-LM model page](https://developers.google.com/edge/litert-lm/models/gemma-4) · [E2B card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) · [E4B card](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) · [Apache-2.0 announcement](https://opensource.googleblog.com/2026/03/gemma-4-expanding-the-gemmaverse-with-apache-20.html)
- Qwen3.5: [0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B) · [2B](https://huggingface.co/Qwen/Qwen3.5-2B) · [litert-community/Qwen3.5-2B](https://huggingface.co/litert-community/Qwen3.5-2B)
- DictaLM 3.0: [paper arXiv 2602.02104](https://arxiv.org/abs/2602.02104) · [1.7B-Instruct card](https://huggingface.co/dicta-il/DictaLM-3.0-1.7B-Instruct) · [DictaLM 2.0 tokenizer numbers](https://arxiv.org/pdf/2407.07080) · [Hebrew leaderboard](https://huggingface.co/blog/leaderboard-hebrew)
- Gemini Nano / ML Kit GenAI: [overview + device lists + background rule](https://developers.google.com/ml-kit/genai) · [Prompt API](https://developers.google.com/ml-kit/genai/prompt/android) · [Summarization API](https://developers.google.com/ml-kit/genai/summarization/android)
- Samsung: [One UI 8 rollout (Personal Data Engine exclusive to Samsung apps)](https://news.samsung.com/uk/samsung-begins-official-rollout-of-one-ui-8-to-galaxy-devices) · [Exynos AI Studio](https://semiconductor.samsung.com/news-events/tech-blog/unpacking-samsungs-comprehensive-on-device-ai-sdk-toolchain-strategy)
- Others: [MediaPipe LLM Inference (Android maintenance-only)](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android) · [llama.cpp grammars](https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md) · [ONNX Runtime GenAI Java](https://onnxruntime.ai/docs/genai/api/java.html) · [ORT GenAI constrained decoding](https://www.mintlify.com/microsoft/onnxruntime-genai/guides/constrained-decoding) · [mobile runtime comparison, arXiv 2604.24636](https://arxiv.org/html/2604.24636v1) · [LLMs on COTS mobile devices, arXiv 2410.03613](https://arxiv.org/html/2410.03613v5)
