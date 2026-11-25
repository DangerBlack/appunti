# Appunti

Android app that records university lectures (up to 3 hours and more) and turns them into text
**on the phone, offline and for free**. Built for non-technical users: one button to record,
one to transcribe.

- Recording runs in a foreground service and survives sudden crashes (AAC/ADTS → M4A).
- Offline transcription with [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
  (Italian + 24 other languages, with punctuation) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx),
  in chunks cut by the Silero VAD: if interrupted, it resumes where it stopped.
- Audio (single copy) and `.txt`/`.srt` copies live in `Documents/Appunti/<lecture>/`, so they survive uninstalling the app.
- Built-in editor for corrections, find & replace, search across lectures.
- Import audio recorded with other apps (button or the "Share" menu).

## AI assistant (optional)

From the gear icon you can connect any OpenAI-compatible service (endpoint, key, model,
extra JSON parameters). Transcribed lectures then show **Summarize** and **Clean up**, which create
separate, editable documents that are also exported to Documents. Long texts are sent in chunks
(`ai/AiTasks.kt`). For reasoning models, disabling thinking is recommended,
e.g. vLLM/Qwen: `{"chat_template_kwargs": {"enable_thinking": false}}`.

## Languages

UI in English (`values/`) and Italian (`values-it/`); it follows the phone language
or the per-app language (Android 13+).

## Model

The model (~670 MB) is not bundled in the APK: the app downloads it on first launch
(Wi-Fi only, or over mobile data if the user taps "Download now with mobile data") from
`MODEL_BASE_URL` in `app/build.gradle.kts`, and verifies size and SHA-256 (`asr/ModelStore.kt`).
To populate your own mirror:

```bash
scripts/fetch-models.sh /path/to/models   # then upload the folder to MODEL_BASE_URL
```

## Build

```bash
scripts/fetch-deps.sh                # downloads the sherpa-onnx AAR (checksum verified)
./gradlew assembleRelease            # APK for arm64 phones
./gradlew assembleDebug -Pemulator   # also includes x86_64 for the emulator
```

Phone requirements: Android 10+, 64-bit, ~1 GB free, 6 GB RAM recommended
(transcription uses ~1.3–1.6 GB).

## Releases

Pushing a tag `vX.Y.Z` that matches `versionName` runs `.github/workflows/release.yml`,
which builds the APK, signs it with `scripts/sign-release.sh` and attaches it to a GitHub release.
Releases use APK Signature Scheme v3 key rotation: `signing/lineage.bin` links the release key
to the key used for the first builds, so updates install over older versions.
Repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`.

## License

Apache-2.0. The Parakeet model is CC-BY-4.0 (NVIDIA); sherpa-onnx is Apache-2.0.

## Support

If Appunti is useful to you, you can [buy me a coffee on Ko-fi](https://ko-fi.com/dangerblack).
