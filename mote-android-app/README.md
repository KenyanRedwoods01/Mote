# Mote — Hybrid-AI Onscreen Bots & Generative UI for Android

Built in **100% native Kotlin 2.x + Jetpack Compose** for **#BuildHybridAI 2026**.

## Architecture Highlights
- **Hybrid AI via Firebase AI Logic** (`com.google.firebase:firebase-ai:18.2.0` + `firebase-ai-ondevice:16.0.0-beta08`):
  - Runs short intents, private summaries, and `<200`-token Generative UI JSON payloads offline on **Gemini Nano 4** (`InferenceMode.PREFER_ON_DEVICE`).
  - Automatically falls back to **Gemini Flash** in the cloud for larger contexts, unsupported devices, or AICore `ErrorCode.BUSY` / battery quota errors.
- **Foreground Scrim Handoff (`HybridInferenceActivity`)**: Launches a zero-animation transparent foreground `ComponentActivity` when a floating bot is tapped over another app so on-device Gemini Nano 4 runs legally in the foreground.
- **Customizable Onscreen Bots**: Floating `WindowManager` ComposeView bots synced via **Firebase Auth** and **Cloud Firestore**, with **Firebase Remote Config** controlling hybrid thresholds.
- **Security First**: **Firebase App Check** enforced, local on-device PII redaction, `FLAG_SECURE` + banking app blocklist, and a strict Kotlin `sealed interface MoteAction` allowlist.

## Quick Start (Android Studio)
1. Open this folder (`mote-android-app`) in **Android Studio**.
2. In the [Firebase Console](https://console.firebase.google.com/), create a project, enable **AI Services > AI Logic** (Gemini Developer API), **App Check**, **Authentication** (Google / Anonymous), **Cloud Firestore**, and **Remote Config**.
3. Register Android package `com.mote.app` and download `google-services.json` into `app/google-services.json` (git-ignored).
4. Run the app on a physical Android device (API 26+, Android 14+ with AICore for Gemini Nano 4).
5. Copy the App Check debug token printed in Logcat (`DebugAppCheckProvider`) and register it in **Firebase Console > App Check > Apps > Manage debug tokens**.

## Attribution
Project structure and local-first recovery patterns inspired by native Kotlin Android LLM architectures such as [local-llms-on-android](https://github.com/dineshsoudagar/local-llms-on-android) (MIT). All Firebase AI Logic hybrid routing, floating ComposeView bots, Generative UI rendering, and AccessibilityService control code in this repository are original work built for #BuildHybridAI 2026 under Apache 2.0.
