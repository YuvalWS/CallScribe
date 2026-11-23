# Third-party notices

CallScribe's own code is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). The app
depends on the libraries and downloads the models listed below. Each keeps its own license, which
applies to that component. Where a license requires notice text, the full text is in the component's
repository or package.

## Libraries shipped in the app

| Component | Version | License | Link |
|---|---|---|---|
| sherpa-onnx (on-device speech recognition, prebuilt AAR) | 2.0.3 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (bundled inside the sherpa-onnx AAR) | — | MIT | https://github.com/microsoft/onnxruntime |
| anthropic-java (Claude API SDK) | 3.8.0 | Apache-2.0 | https://github.com/anthropics/anthropic-sdk-java |
| OkHttp | 5.5.1 | Apache-2.0 | https://github.com/square/okhttp |
| kotlinx.serialization (JSON) | 1.13.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| AndroidX and Jetpack Compose (core, activity, lifecycle, navigation, work, datastore, Room, Material 3) | see `app/build.gradle.kts` | Apache-2.0 | https://developer.android.com/jetpack |
| Google Play services — Auth | 22.0.0 | Google proprietary (Android SDK terms) | https://developers.google.com/android/guides/setup |

Test-only dependencies (JUnit, org.json, sqlite-jdbc) are not shipped in the app.

## Models downloaded by the user

CallScribe does not include any model. The user downloads each model from its source, and the
license below governs that model.

| Model | Used for | License | Link |
|---|---|---|---|
| ivrit.ai Zipformer 2 (66M, streaming, int8) — Hebrew | On-device transcription | CC-BY-4.0 | https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he |
| ivrit.ai Whisper large-v3-turbo (ggml / CTranslate2) — Hebrew | Cloud transcription (RunPod endpoint) | Apache-2.0 | https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml · https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ct2 |
| ivrit.ai runpod-serverless (endpoint template) | Cloud transcription | MIT | https://github.com/ivrit-ai/runpod-serverless |

Attribution for the Zipformer model (CC-BY-4.0): the model is a mirror of
[thewh1teagle/zipformer2-66m-streaming-he](https://huggingface.co/thewh1teagle/zipformer2-66m-streaming-he),
based on [k2-fsa/icefall](https://github.com/k2-fsa/icefall) (Apache-2.0), and was trained on audio from
[ivrit.ai](https://huggingface.co/ivrit-ai).

The ivrit.ai training datasets carry their own terms (the audio-vad dataset card says the material may be
used only to train AI models). CallScribe does not redistribute the datasets. Users who want to be sure
how those terms apply to a downloaded model should check with ivrit.ai.
