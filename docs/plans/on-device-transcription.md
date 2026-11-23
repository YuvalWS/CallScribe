# On-device transcription (no cloud)

Status: implemented on branch `claude/on-device-transcription-9fbdd4`. §6 has the measurements.
Labels: **[measured]** = we measured it, **[source]** = a published number (linked), **[estimate]** = our extrapolation.

## 1. How the ivrit.ai app does it (research, 2026-11-23)

The ivrit.ai Android app ([ivrit-ai/ivrit-app](https://github.com/ivrit-ai/ivrit-app),
[docs/turbo-android.md](https://github.com/ivrit-ai/ivrit-app/blob/master/docs/turbo-android.md)) has **two** on-device engines:

| Path | Engine | Model | Used for |
|---|---|---|---|
| "Turbo" | whisper.cpp 1.9.5 built from source (NDK), CPU + OpenCL/Vulkan/WebGPU GPU backends | ivrit-ai whisper-large-v3-turbo, **q8_0** ggml, quantized by *their own server* from the f16 file (not on Hugging Face) | Background transcription of recordings on capable phones (arm64 with dotprod + fp16, ~6 GB RAM, "2023 on, mid-range and up") |
| Zipformer | **sherpa-onnx** (bundled) | [ivrit-ai/zipformer2-66m-streaming-he](https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he), int8 | Live transcription, and recordings on phones that fail the Turbo check |

Published Turbo numbers [source: turbo-android.md]: Galaxy S25 (Snapdragon 8 Elite), q8_0, CPU only, greedy ≈ **1.1× real time**, beam 5 ≈ 3×;
with the OpenCL GPU encoder 0.25–0.6×. Firebase Test Lab, 5-min clip: S24 136 s, A56 302 s, Pixel 10 751 s; **Pixel 6/7 and A54 timed out**.
Battery/heat: "Not verified" in their docs. Their mitigations: transcribe watched folders only while charging, download the model only on Wi-Fi + charging.

## 2. Models available in on-device formats (Hugging Face)

| Repo | File(s) | Size | Format |
|---|---|---|---|
| [ivrit-ai/whisper-large-v3-turbo-ggml](https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml) | ggml-model.bin | 1.62 GB | ggml f16 (only quant published) |
| [ivrit-ai/whisper-large-v3-ggml](https://huggingface.co/ivrit-ai/whisper-large-v3-ggml) | ggml-model.bin | 3.1 GB | ggml f16 |
| [ivrit-ai/whisper-large-v3-turbo-onnx](https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-onnx/tree/main/onnx) | encoder+decoder | fp16 ≈ 1.6 GB, int8 ≈ 1.1 GB, q4 ≈ 0.75 GB, q4f16 ≈ 0.56 GB | HF/optimum ONNX (transformers.js layout, not sherpa-onnx's Whisper layout) |
| [ivrit-ai/zipformer2-66m-streaming-he](https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he) | sherpa-onnx/encoder.int8.onnx 70.5 MB, decoder 1.0 MB, joiner 0.8 MB, tokens.txt 17 KB | **72.3 MB** | sherpa-onnx transducer, int8 |

No small/base Hebrew Whisper exists. ivrit.ai's own q8_0 Turbo (~0.87 GB [estimate]) is not published.

## 3. The trade-off, answered with numbers

Phone: Galaxy S20 FE (Snapdragon 865: 1×A77 @2.84 + 3×A77 @2.42 + 4×A55, 6 GB RAM, 4500 mAh ≈ 17 Wh).

| | Cloud (today) | On-device **Zipformer** (this change) | On-device **Turbo** (whisper.cpp, not done) |
|---|---|---|---|
| Model download | 0 | **72 MB** once [measured, HF API] | 1.62 GB (f16; the only file on HF) |
| APK growth | 0 | **+35 MB** release APK (32 → 67 MB) [measured]: arm64 libs stored uncompressed (onnxruntime 22 MB + sherpa 11 MB) | ~2–4 MB [estimate], but needs NDK + CMake build (~1.5 GB NDK on the build PC) or a single-maintainer prebuilt AAR |
| RAM while running | — | ~150–250 MB [estimate] | ~2 GB with f16 [estimate] |
| 1-min call | ~15–30 s incl. cold start | **25 s** [measured, 2 threads] | ~2–3 min [estimate: S25 CPU greedy 1.1×, S865 ≈ 2× slower] |
| 10-min call | ~25 s (14-min call = 14 s GPU, PLAN §4a) | **~4 min** [estimate: measured RTF 0.41 × 10] | ~20–30 min [estimate]; ivrit.ai's test lab saw older phones time out |
| Battery per minute of audio | ~0 (upload only) | **≈0.1 %** [estimate from measured 44 CPU-s per audio-minute × ~1 W per A77 core, of 17 Wh]; a 10-min call ≈ 1 % | ≈1.5–3 % [estimate: 5 W × 2–3 min of 17 Wh] |
| Cost | ~$0.0002/min | **$0** | $0 |
| Hebrew WER (eval-d1 / eval-whatsapp) [source: model card] | ivrit turbo **5.3 / 7.1** | **5.1 / 8.9** (fp32); int8 sherpa export **5.3 / 8.8** | same as cloud if f16/q8_0 (q8_0 = f16 on ivrit.ai's clip: 3.31 %) |
| Other quality notes | punctuation, good with numbers & English words | numbers inconsistent, English spelled letter by letter [source: model card]; punctuation **is** produced [measured: "שלום. מה נשמע? כן…"] | — |

Takeaway: phone calls are conversational (closest to *eval-whatsapp*), where Zipformer is ~1.8 points worse
(8.8 vs 7.1 WER ≈ 1 extra wrong word in 55). In exchange it is ~5× faster than Turbo on this phone (estimate), needs
a 72 MB download instead of 1.6 GB, and costs nothing. The cloud stays the default; on-device is a privacy / zero-cost option.

## 4. Engine choice

* **sherpa-onnx v2.0.3** (Apache-2.0, 16k★, active), prebuilt AAR from its GitHub release, pinned by SHA-256
  (`4c766e2e…2ec50`). It is **not on Maven Central**, so Gradle downloads it once into the Gradle user home and
  verifies the hash (no 50 MB binary in git, **no NDK needed**). Only `arm64-v8a` is packaged (`abiFilters`).
* Rejected: `dev.ffmpegkit-maintained:whisper-android` (only whisper.cpp AAR on Maven Central — single maintainer,
  4 months old, file-path-only API, unknown CPU flags, paid "Pro" tier); `whisper-jni` (no Android build);
  building whisper.cpp ourselves (needs the NDK, which the build PC has no room for, and Turbo is too slow on a 2020 phone anyway).
* Turbo via whisper.cpp can be added later as a second on-device model behind the same UI when the NDK
  decision is made (it would also need us to host/produce a q8_0 file).

## 5. Design

* `providers/ondevice/OnDeviceModel.kt` — pure model spec: HF repo, **pinned revision**, files with size + SHA-256. Unit-tested.
* `providers/ondevice/ModelStore.kt` — download to `noBackupFilesDir/models/<id>/` (app-specific, excluded from backup),
  `.part` file → verify SHA-256 → rename; progress as `StateFlow`; cancel; delete. Size shown before download.
* `providers/ondevice/OnDeviceTranscriber.kt` — implements `Transcriber` (id `ondevice-zipformer-he`, cost $0) plus
  a `LocalTranscriber` extension: decode the recording with `AudioCompressor`'s decoder to 16 kHz mono PCM in a temp file,
  stream it in 0.5 s chunks into a sherpa-onnx `OnlineRecognizer`, cut a segment at each endpoint (silence), report progress.
* `SegmentAssembler` (pure, unit-tested) turns (time, partial text, endpoint) events into `Segment`s + full text.
* `ProcessWorker`: when the transcriber is local, call it directly (no submit/poll), and promote the worker to a
  **foreground service** with a progress notification (`dataSync`, or `mediaProcessing` on Android 15+). If Android
  refuses (app in background), carry on as a normal worker — a long call still finishes inside WorkManager's 10-min window.
* Battery: setting **"Automatic transcription only while charging"** (default on) adds a `requiresCharging`
  constraint to automatic jobs; the Transcribe button always runs at once. Threads setting (default 4 = big cores).
* Settings → Providers: a radio choice *RunPod (cloud)* / *On this phone*; the on-device card shows model size,
  Download (with progress) / Delete, threads, the charging switch, and Test (loads the model, runs 1 s of silence).
* No Room schema change. Network constraint is dropped for local transcription when no analysis/export step needs it.

## 6. Measurements (Galaxy S20 FE, Android 13) — 2026-11-23

Debug build, hook `DEBUG_ONDEVICE` (see `app/src/debug/.../DebugReceiver.kt`), one real 59.95 s call (mono AAC 44.1 kHz).

| Run | Threads | Wall time | CPU time | RTF |
|---|---|---|---|---|
| 1st (cold: model just downloaded, page cache cold) | 4 | 39.4 s | 90.8 s | 0.66 |
| 2nd | **2** | **24.5 s** | 44.2 s | **0.41** |
| 3rd | 4 | 35.1 s | 82.2 s | 0.59 |
| 4th | 1 | 29.4 s | 30.3 s | 0.49 |

* Of the wall time, **4.5 s** is decoding the AAC to 16 kHz PCM (`AudioCompressor.decodeToMono`).
* More threads are *slower*: the streaming model processes small chunks, and extra ONNX Runtime threads mostly spin
  (4 threads burn 2× the CPU of 2 for less speed). Default is therefore **2 threads**; the setting allows 1–4.
* Model download: **72 MB in 5.3 s** over Wi-Fi, SHA-256 verified.
* Output: 5 segments, readable Hebrew **with punctuation** (contrary to our reading of the model card), e.g.
  "שלום. מה נשמע? כן אני צריך אותך בבקשה…". One clear mis-hearing in the first sentence ("בשרסה, פרקים").
* **Battery: not measurable on this run.** The phone was on USB (reported "not charging", level 84 % before and after,
  `CURRENT_NOW`/charge counter unsupported → 0). Estimate from CPU time: 44 CPU-seconds on Cortex-A77 cores at
  roughly 1 W each ≈ 45–50 J per minute of audio ≈ **0.08 % of the 17 Wh battery per minute of audio** (≈ 1 % for a 10-min call).
* APK: arm64 native libraries add **33.0 MB stored uncompressed** (libonnxruntime 22.2 MB, sherpa-onnx 10.7 MB);
  release APK **67.0 MB vs ~32 MB before (+35 MB)**. Storing the libs compressed (`useLegacyPackaging`) would shrink the
  download to ~+13 MB [estimate] but Android then extracts them, costing more space on the phone overall, so we keep the default.

## 7. Known limits / follow-ups

* If Android refuses the foreground service (automatic job started while the app is in the background and the app is
  not exempt from battery optimisation), WorkManager stops the worker after 10 minutes — calls longer than ~20 min
  could then fail. Samsung's "Never sleeping apps" (README) normally exempts the app.
* Turbo (whisper.cpp) as a second, more accurate on-device model: needs the NDK decision (≈1.5 GB on the build PC) and a q8_0 file.
* No speaker separation on device.
