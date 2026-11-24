# Plan: more languages, more phones, better caller identity (status: idea, not started)

## 1. Transcription in other languages

Today: ivrit.ai (Hebrew) on RunPod, or the Hebrew on-device model. Candidates for a language-neutral provider,
all bring-your-own-key (prices to re-check before building):

| Option | How | Notes |
|---|---|---|
| **Gemini (audio in)** | `generateContent` with the audio file + "transcribe" prompt | Many languages, the same key as the AI summary, could transcribe **and** summarize in one call. Free tier has the privacy caveat already shown in the app. Timestamps/speakers are approximate |
| **OpenRouter audio models** | chat request with `input_audio` to an audio-capable model | One key for everything; model choice varies; check which models accept audio |
| OpenAI `gpt-4o-mini-transcribe` / `whisper-1` | `/v1/audio/transcriptions` | Simple, cheap, 25 MB limit (our compressor already fits) |
| Groq Whisper large-v3(-turbo) | OpenAI-compatible endpoint | Very cheap and fast |
| Generic Whisper on RunPod | RunPod's faster-whisper worker | Same flow as ivrit.ai, any language |
| On device | sherpa-onnx multilingual model (Whisper small / SenseVoice) | Free and private, weaker than cloud |

Design: a `Transcriber` per option (the interface already exists), a **language** setting (auto-detect by default),
and the onboarding transcription step offers "Hebrew (ivrit.ai)" vs "Other languages". The AI summary prompt
already takes the language from the transcript; make the summary language a setting.

## 2. Other phones

- **Today:** only Samsung is supported. The folder is a setting (Settings → folders, default `Recordings/Call`), but
  the file-name parser (name/number, spam label, time) knows Samsung's format only.
- Known locations (to verify on real devices before claiming support): Xiaomi/Redmi `MIUI/sound_recorder/call_rec`;
  OnePlus/Oppo/Realme `Recordings/Call` or `Music/Recordings/Call Recordings`; Honor/Huawei `Sounds/CallRecord`;
  Motorola/Nokia vary. **Google Pixel** keeps recordings inside the Phone app's private storage — not reachable by
  other apps, so Pixel can't be supported.
- Plan: a parser per vendor (detected from `Build.MANUFACTURER` + folder), auto-detect folders on first scan, a
  fallback that uses the file's date when the name doesn't parse, and a "my phone isn't detected" help link.

## 3. Name ↔ number (contact saved after the call)

Facts checked on the test phone: Samsung writes the **contact name** into the file name when the caller is a contact,
otherwise the **number**; the file's metadata holds nothing more (title = file name, album "Call", no number).
So today a call recorded before the caller was saved keeps showing the number, and a call with a contact shows only
the name.

Plan:
- With contacts access (already asked for contact-group policies): look up **number → current contact name** for
  display and policies (`PhoneLookup`), and **name → numbers** so contact policies match both kinds of file.
  Re-resolve when contacts change; keep the original file-name value too.
- Optional, later: match each recording to the **call log** (`READ_CALL_LOG`, time ± duration) to get the exact
  number even for contacts. Google Play treats call-log access as sensitive; spam detection / caller ID is an
  allowed use, but it needs a declaration — keep it optional and off by default.
