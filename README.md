# Mote — Hybrid On-Device & Cloud AI Assistant for Android

[![Platform](https://img.shields.io/badge/Platform-Android%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Firebase AI Logic](https://img.shields.io/badge/Firebase-AI%20Logic%20Hybrid%20SDK-FFCA28?logo=firebase&logoColor=black)](https://firebase.google.com/docs/ai-logic/hybrid)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose%20M3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)
[![Hackathon](https://img.shields.io/badge/%23BuildHybridAI-Android%20Track-0EA5E9)](https://firebase.google.com/docs/ai-logic/hybrid)

**Mote** is an Android hybrid AI agent and Generative UI companion built with the **Firebase AI Logic Hybrid Inference SDK**. It runs fast, private, zero-cost prompts on-device with **Gemini Nano via Android AICore** when supported and cool, and automatically falls back to **Cloud Gemini** via Firebase AI Logic when a device lacks AICore, experiences thermal throttling, runs low on battery, or handles multimodal vision and complex multi-step tool chains.

Beyond chat, Mote turns natural language into **verified, sequential Android actions** — chaining screenshots, OCR, Notes, Chrome searches, Clock alarms/timers/stopwatch, Calendar events, Clipboard transfers, Wi-Fi/Network diagnostics, Camera shutter control, and real-time Speech-to-Text / Text-to-Speech voice relay — while floating persistently over other apps with a draggable Lottie voice orb and confirming every OS action through a live **`EventObserver`** (`BroadcastReceiver` + `ContentObserver`) pipeline before marking it **Execution Verified**.

---

## Table of Contents

1. [Why Mote? (Value Proposition)](#why-mote-value-proposition)
2. [Firebase AI Logic Hybrid Inference (Runtime Implementation)](#firebase-ai-logic-hybrid-inference-runtime-implementation)
3. [Firebase Product Stack](#firebase-product-stack)
4. [Core Features & Architecture](#core-features--architecture)
5. [System Health & Adaptive Inference Dashboard](#system-health--adaptive-inference-dashboard)
6. [ToolExecutionSync & EventObserver Verification Pattern](#toolexecutionsync--eventobserver-verification-pattern)
7. [What Was Built During #BuildHybridAI](#what-was-built-during-buildhybridai)
8. [Getting Started & Local Build Instructions](#getting-started--local-build-instructions)
9. [Permissions & PrivacyGuard](#permissions--privacyguard)
10. [Project Structure](#project-structure)
11. [Demo Video & Hackathon Submission Checklist](#demo-video--hackathon-submission-checklist)
12. [Documentation & Community](#documentation--community)
13. [License](#license)

---

## Why Mote? (Value Proposition)

On-device AI is fast, private, and free to run — yet hardware support varies widely across Android devices, and running sustained local inference on a hot or low-battery phone degrades user experience. Meanwhile, most mobile AI apps either lock users into cloud-only latency or trap them inside a single chat screen where the assistant *claims* it did something without actually verifying Android OS state.

**Mote solves all three problems in one native Jetpack Compose application:**

- **One Hybrid SDK, Zero Dead Ends:** Uses `com.google.firebase:firebase-ai` + `com.google.firebase:firebase-ai-ondevice` with official `OnDeviceConfig(mode = ...)` so Gemini Nano runs locally on supported Android devices and seamlessly falls back to Cloud Gemini everywhere else.
- **Thermal & Battery-Aware Provider Switching:** Real-time Android `PowerManager` thermal telemetry and battery sensors dynamically shift `OnDeviceConfig` from `PREFER_ON_DEVICE` to `PREFER_IN_CLOUD` at ≥40.0°C, Moderate+ thermal throttling, Power Saver mode, or ≤25% unplugged battery — transparently visualized in the **System Health & Inference Dashboard**.
- **Truth-in-Execution Tool Chains:** Every AI tool invocation (native Firebase `Tool.functionDeclarations` or streamed JSON tool tags) flows through a persistent Room-backed **Sequential Tool Orchestrator** and **`ToolExecutionSyncService`** (`BroadcastReceiver` + `ContentObserver` + Room diffs), so the UI only marks a step **Execution Verified** after confirmable OS completion.

---

## Firebase AI Logic Hybrid Inference (Runtime Implementation)

Mote imports and executes the official Firebase AI Logic Hybrid SDK directly at runtime — not just in documentation.

### Gradle Dependencies (`app/build.gradle.kts`)

```kotlin
implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
implementation("com.google.firebase:firebase-ai")
implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta08")
implementation("com.google.firebase:firebase-appcheck-debug")
implementation("com.google.firebase:firebase-appcheck-recaptcha")
implementation("com.google.firebase:firebase-auth")
implementation("com.google.firebase:firebase-firestore")
implementation("com.google.firebase:firebase-config")
implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
```

### Verified Runtime Code Entry Points

| Capability | File Path | Runtime APIs Called |
| --- | --- | --- |
| **Hybrid `GenerativeModel` + `OnDeviceConfig`** | `app/src/main/java/com/mote/app/ai/HybridAiRouter.kt` | `Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(..., onDeviceConfig = OnDeviceConfig(mode = sdkHybridMode))`, `InferenceMode` (`PREFER_ON_DEVICE`, `ONLY_ON_DEVICE`, `PREFER_IN_CLOUD`, `ONLY_IN_CLOUD`), `chunk.inferenceSource` (`InferenceSource.ON_DEVICE` / `InferenceSource.IN_CLOUD`) |
| **Native Firebase Function Calling** | `app/src/main/java/com/mote/app/ai/FirebaseNativeToolDeclarations.kt` | `Tool.functionDeclarations(...)`, `FunctionDeclaration`, `Schema.obj/string/integer/boolean`, `chunk.functionCalls` |
| **AICore / Gemini Nano Availability & Download** | `app/src/main/java/com/mote/app/ai/AiCoreNanoManager.kt` | `Generation.getClient()`, `checkStatus()` (`FeatureStatus.AVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`, `UNAVAILABLE`), `download()` (`DownloadStatus.DownloadStarted/DownloadProgress/DownloadCompleted/DownloadFailed`) |
| **Firebase App Check Enforcement** | `app/src/main/java/com/mote/app/security/AppCheckInit.kt` | `FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())` (debug) & `RecaptchaAppCheckProviderFactory` (release) |
| **Adaptive Hybrid Routing** | `app/src/main/java/com/mote/app/ui/MoteViewModel.kt` | Dynamically selects `OnDeviceConfig` mode based on `SystemHealthManager` thermal/battery state, routing primary chat and Generative UI through cached `GenerativeModel` instances |
| **Cloud Sync & Auth** | `app/src/main/java/com/mote/app/data/FirestoreRepository.kt`, `app/src/main/java/com/mote/app/auth/GoogleAuthManager.kt` | Jetpack `CredentialManager` (`GetSignInWithGoogleOption`), `FirebaseAuth`, per-user `FirebaseFirestore` sync protected by `firestore.rules` |

---

## Firebase Product Stack

- **Firebase AI Logic (`firebase-ai` + `firebase-ai-ondevice`)**: Unified streaming chat, multimodal screenshot Vision OCR, structured JSON Generative UI (`responseSchema`), native `Tool.functionDeclarations`, and automatic on-device ↔ cloud fallback.
- **Firebase App Check (`firebase-appcheck-debug` / `firebase-appcheck-recaptcha`)**: Protects Gemini backend calls; supports debug tokens in development/judging builds and reCAPTCHA attestation in release.
- **Firebase Authentication (`firebase-auth`)**: Google Sign-In via Jetpack `CredentialManager` (`GetSignInWithGoogleOption`) plus local-only Guest Mode.
- **Cloud Firestore (`firebase-firestore`)**: Syncs custom Bot Personas and chat history across devices with zero-trust per-user security rules (`firestore.rules` + emulator test suite `firestore.test.js`).
- **Firebase Remote Config (`firebase-config`)**: Bundled for remote model/feature configuration.

---

## Core Features & Architecture

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                         Jetpack Compose M3 UI Layer                       │
│  ChatHubScreen · GenUI Cards · Model Hub · Tools & Permissions · Settings │
│   SystemHealthInferenceDashboard · ToolExecutionLog · Floating Lottie Orb │
└───────────────────────────────┬───────────────────────────────────────────┘
                                │ StateFlow / MVVM (MoteViewModel)
┌───────────────────────────────▼───────────────────────────────────────────┐
│                    Hybrid AI & Adaptive Routing Engine                    │
│  ┌──────────────────────────────┐  ┌───────────────────────────────────┐  │
│  │       HybridAiRouter         │  │       SystemHealthManager         │  │
│  │ • Cached GenerativeModel LRU │◄─┤ • Battery Temp (°C) & Power Draw  │  │
│  │ • OnDeviceConfig(mode = ...) │  │ • PowerManager Thermal Throttling │  │
│  │ • Native Tool Declarations   │  │ • Auto Cloud Offload (≥40°C/≤25%) │  │
│  │ • Gemini Nano <-> Cloud Auto │  └───────────────────────────────────┘  │
│  └──────────────┬───────────────┘                                         │
│                 │ FunctionCallPart + IncrementalJsonStreamParser          │
│  ┌──────────────▼──────────────────────────────────────────────────────┐  │
│  │                  SequentialToolOrchestrator                         │  │
│  │ • Natural Language -> Atomic Tool Chain + Room ToolChainState Pipe  │  │
│  │ • Clock (Alarm/Timer/Stopwatch) · Calendar · Notes · Clipboard      │  │
│  │ • Chrome/App Search · Screenshot+OCR · Camera · Wi-Fi/Ping · Cursor │  │
│  │ • Phone Dialer + Live STT <-> AI <-> TTS Voice Call Relay           │  │
│  └──────────────┬──────────────────────────────────────────────────────┘  │
│                 │ PreExecutionSnapshot + Post-Execution Verification      │
│  ┌──────────────▼──────────────────────────────────────────────────────┐  │
│  │           ToolExecutionSyncService + AndroidSystemEventObserver     │  │
│  │ • BroadcastReceiver: AlarmManager, AudioManager, NotificationMgr    │  │
│  │ • ContentObserver: Settings.System Brightness, CalendarContract     │  │
│  │ • ClipboardManager Listener · Accessibility Foreground Observer     │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

### Key Capabilities

1. **Hybrid On-Device & Cloud Chat + Multimodal Vision**: Streams responses using cached Firebase `GenerativeModel` instances with `OnDeviceConfig`, reporting exact latency, TTFT, tokens/sec, and whether the SDK served the response `On-Device (Gemini Nano)` or `Cloud (Firebase AI Logic)`. Also supports opt-in local `.litertlm` / `.task` models via Google AI Edge LiteRT-LM.
2. **Generative UI Engine (11 Interactive Components)**: Streams structured JSON cards (`metric_grid`, `bar_chart`, `calendar`, `checklist`, `comparison_table`, `timeline`, `quiz_card`, `slider`, `toggle`, `action_chip`, `reply_pills`, `info_card`) directly in chat or in the dedicated GenUI Studio.
3. **Sequential Tool Chain Orchestrator (`ToolChainState` Room Persistence)**: Maps complex prompts (e.g., *"Take a screenshot → OCR analysis → Save to Notes"* or *"Open Chrome, search Android AI news, and take a screenshot"*) into atomic tool chains that pass verified outputs between steps.
4. **Tool Execution Log UI**: Displays chronological multi-step tool chains in chat with live status badges (`Pending`, `Executing`, `Execution Verified`, `Failed`) and verifiable receipt IDs.
5. **System-Wide Draggable Floating Lottie Overlay**: Runs persistently over external Android apps (Chrome, Phone, Camera, Maps) via `AccessibilityFloatingWindowManager` (`TYPE_ACCESSIBILITY_OVERLAY`) or `BotOverlayService` (`TYPE_APPLICATION_OVERLAY`), with touch-drag repositioning, privacy auto-hide on banking/auth screens, and zero-FPS idle rendering for smooth 60/120fps scrolling.
6. **Full Android Tool Suite**:
   - **Clock Tool Set**: Alarms (`addAlarm`, `updateAlarm`, `snoozeAlarm`, `shiftAlarm`, `deleteAlarm`, `listAlarms`), live ticking Countdown Timers, and Stopwatch with Lap splits.
   - **Calendar & Notes**: Room-backed + Android `CalendarContract` event CRUD and searchable Notes.
   - **Screen Capture + Vision OCR**: MediaProjection / Accessibility capture piped into Gemini Vision OCR and downstream tools.
   - **Phone & Live Voice Relay**: Play-compliant `ACTION_DIAL` + live cursor hover/click + real-time Google STT ↔ Gemini ↔ Android TTS call relay.
   - **Camera, Wi-Fi/Network & Live Cursor**: Camera modes/torch/shutter, live Wi-Fi/IP/TCP ping diagnostics, clipboard read/write, and animated visual cursor hover/click/swipe.

---

## System Health & Adaptive Inference Dashboard

Located in **Model Hub** (`SystemHealthInferenceDashboard.kt`), the dashboard gives real-time transparency into why the adaptive engine routes prompts locally vs. to the cloud:

- **Battery Temp Gauge (°C)**: Live temperature reading normalized across a `20°C – 50°C` headroom bar with a marked `40°C Cloud Offload` threshold.
- **Thermal Throttling Status**: Reads Android `PowerManager.currentThermalStatus` (`Cool`, `Warm`, `Moderate`, `High`, `Critical`) and flags active throttling.
- **Power & Battery Draw**: Displays live percentage, charging status, Power Saver mode, and instantaneous discharge current (mA).
- **Active Inference Mode & Decision Explainer**: Displays the active Firebase `OnDeviceConfig` mode (`Prefer On-Device`, `Prefer Cloud`, `Only Cloud`, `Only On-Device`), live AICore Gemini Nano status, and the exact decision reason explaining why the provider switched.
- **Live EventObserver Feed**: Streams real-time OS callbacks captured by `AndroidSystemEventObserver`.

---

## ToolExecutionSync & EventObserver Verification Pattern

Implemented in `ToolExecutionEventObserver.kt` and `ToolExecutionSyncService.kt`, Mote uses an explicit **`EventObserver`** pattern (`SystemActionEventObserver` / `AndroidSystemEventObserver`) to prevent hallucinated "Done!" messages:

1. **Before Execution**: Captures a `PreExecutionSnapshot` (foreground package, Room counts for alarms/events/notes, stopwatch/timer state, clipboard preview, elapsed timestamp).
2. **Active OS Hooks (Background `HandlerThread`)**:
   - `BroadcastReceiver` listening to `AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED`, `android.media.VOLUME_CHANGED_ACTION`, `AudioManager.RINGER_MODE_CHANGED_ACTION`, and `NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED`.
   - `ContentObserver` watching `Settings.System.SCREEN_BRIGHTNESS` and `CalendarContract.Events.CONTENT_URI`.
   - `ClipboardManager.OnPrimaryClipChangedListener` for real-time clipboard write confirmations.
3. **Post-Execution Confirmation**: `ToolExecutionSyncService` awaits the corresponding OS callback (via non-blocking `SharedFlow` `awaitEvent`) and verifies Room/PendingIntent/ticker diffs before emitting `Execution Verified` to the Generative UI log and chat transcript.

---

## What Was Built During #BuildHybridAI

During the `#BuildHybridAI` competition sprint, Mote was significantly upgraded with:

1. **Official Firebase AI Logic Hybrid SDK Integration**: Added `firebase-ai-ondevice:16.0.0-beta08` + `genai-prompt:1.0.0-beta4`, refactored primary chat and Generative UI to cached `Firebase.ai(...).generativeModel(..., onDeviceConfig = OnDeviceConfig(...))`, and wired real `InferenceSource` reporting and `AiCoreNanoManager` download/readiness checks.
2. **Native Firebase Function Calling**: Implemented `FirebaseNativeToolDeclarations` (`Tool.functionDeclarations`) seamlessly unified with `IncrementalJsonStreamParser`.
3. **System Health & Inference Dashboard**: Built a real-time thermal, battery, throttling, and provider-switching visualizer wired directly to adaptive `OnDeviceConfig` routing.
4. **Sequential Tool Chain Orchestrator & `ToolChainState` Room Persistence**: Built chained multi-step execution with intermediate state piping and chronological `ToolExecutionLog` UI cards.
5. **`ToolExecutionSyncService` + `EventObserver` Pattern**: Added live `BroadcastReceiver` and `ContentObserver` verification hooks for AlarmManager, AudioManager, NotificationManager (DND), Brightness, CalendarContract, and ClipboardManager.
6. **Draggable Floating Lottie Overlay & Stability/Performance Polish**: Added touch-drag repositioning, AccessibilityService lifecycle & null-safety hardening, zero-FPS idle orb rendering, deduplicated telemetry flows, and responsive Material 3 card layouts.

---

## Getting Started & Local Build Instructions

### Prerequisites

- **Android Studio** (Meerkat / Narwhal or newer) with JDK 17
- **Android SDK 36** (`compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`)
- A **Firebase project** with **Firebase AI Logic (Gemini Developer API)**, **App Check**, **Authentication (Google Sign-In)**, and **Cloud Firestore** enabled

### 1. Clone the Repository

```bash
git clone https://github.com/<your-username>/mote-hybrid-ai-android.git
cd mote-hybrid-ai-android
```

### 2. Configure Firebase

1. In the [Firebase Console](https://console.firebase.google.com/), register an Android app with package name `com.mote.app`.
2. Enable **Firebase AI Logic** (Gemini Developer API backend), **Firebase Authentication** (Google provider), **Cloud Firestore**, and **App Check**.
3. Download your `google-services.json` and place it in `app/google-services.json` (this project's config is wired via the Google Services Gradle plugin).
4. Deploy Firestore security rules:
   ```bash
   firebase deploy --only firestore:rules
   ```

### 3. Configure Firebase App Check Debug Token (Development & Judging)

Firebase AI Logic requires App Check. Mote automatically installs `DebugAppCheckProviderFactory` in debug builds (`app/src/main/java/com/mote/app/security/AppCheckInit.kt`):

1. Run the debug build on an Android device or emulator.
2. Copy the debug secret printed in Logcat (`DebugAppCheckProvider: Enter this debug secret into the allow list in the Firebase Console`).
3. Register it under **Firebase Console → App Check → Apps → Manage debug tokens**.

*(For release builds, configure your reCAPTCHA Enterprise site key in `BuildConfig` / `AppCheckInit.kt`.)*

### 4. Build & Run

```bash
# Build debug APK
gradle :app:assembleDebug

# Run local unit tests (JVM)
gradle :app:testDebugUnitTest

# Run Firestore Security Rules tests (requires Firebase Emulator Suite)
node --test firestore.test.js
```

> **Tip for On-Device Gemini Nano Testing:** Run on an AICore-enabled Android device (e.g., Pixel 9 / 10 series or supported Android 14+ flagship), open **Model Hub → Firebase AI Logic Hybrid SDK**, and tap **Check AICore / Download Nano**. On emulators or non-AICore devices, the SDK automatically falls back to Cloud Gemini and displays `Cloud Fallback` in the dashboard.

---

## Permissions & PrivacyGuard

Mote follows strict Google Play least-privilege principles:

- **Zero broad storage permissions** — media picking uses Android Photo Picker (`ActivityResultContracts.PickVisualMedia`).
- **Play-compliant Phone & Network controls** — uses `ACTION_DIAL` (user-visible dialer) and `Settings.Panel.ACTION_WIFI` / `ACTION_INTERNET_CONNECTIVITY`.
- **Runtime opt-in grants** — Calendar, Camera, Microphone, Notifications, Exact Alarms, Overlay, UsageStats, MediaProjection, and Accessibility are each individually auditable and toggleable inside **Tools & Permissions**.
- **PrivacyGuard (`app/src/main/java/com/mote/app/security/PrivacyGuard.kt`)** — automatically hides the floating overlay and blocks screen inspection on banking, password-manager, and authentication packages, and redacts PII/passwords before context reaches any model.

---

## Project Structure

```text
├── app/
│   ├── build.gradle.kts                      # Firebase AI Logic Hybrid SDK, App Check, Room, Compose M3
│   └── src/main/java/com/mote/app/
│       ├── ai/
│       │   ├── HybridAiRouter.kt             # Cached GenerativeModel + OnDeviceConfig + Cloud fallback
│       │   ├── AiCoreNanoManager.kt          # AICore / ML Kit GenAI Prompt API status & download
│       │   ├── FirebaseNativeToolDeclarations.kt # Official Tool.functionDeclarations schema
│       │   ├── SequentialToolOrchestrator.kt # Multi-step tool chaining & truth-in-execution guard
│       │   └── GenUiToolRegistry.kt          # 13+ Android tool families
│       ├── control/
│       │   ├── ToolExecutionSyncService.kt   # Post-execution verification engine
│       │   ├── ToolExecutionEventObserver.kt # BroadcastReceiver & ContentObserver hooks
│       │   ├── SystemHealthManager.kt        # Live battery temp & PowerManager thermal routing
│       │   ├── BatteryResourceMonitor.kt     # Real-time current/wattage telemetry
│       │   └── ClockToolsManager.kt          # Alarms, Countdown Timers, Stopwatch
│       ├── service/
│       │   ├── MoteAccessibilityService.kt   # Hardened Accessibility + visual cursor automation
│       │   ├── AccessibilityFloatingWindowManager.kt # TYPE_ACCESSIBILITY_OVERLAY Lottie host
│       │   └── BotOverlayService.kt          # TYPE_APPLICATION_OVERLAY foreground service
│       ├── security/
│       │   ├── AppCheckInit.kt               # Firebase App Check debug/release setup
│       │   └── PrivacyGuard.kt               # Banking blocklist & PII redaction
│       └── ui/
│           ├── MoteViewModel.kt              # MVVM orchestration & adaptive mode switching
│           ├── screens/                      # ChatHub, GenUI Studio, Model Hub, Tools, Settings
│           └── components/
│               ├── SystemHealthInferenceDashboard.kt # Live thermal, battery & hybrid provider UI
│               ├── FirebaseHybridStatusCard.kt       # OnDeviceConfig & AICore Gemini Nano UI
│               └── ToolExecutionLogComponents.kt     # Chronological tool chain verification cards
├── firestore.rules                           # Zero-trust per-user Firestore security rules
├── firestore.test.js                         # Automated Firestore rules verification tests
├── README.md                                 # Project overview & #BuildHybridAI documentation
├── LICENSE                                   # Apache License 2.0
├── SECURITY.md                               # Security architecture & vulnerability disclosure
├── CONTRIBUTING.md                           # Contribution & testing guide
├── CODE_OF_CONDUCT.md                        # Community standards
└── CHANGELOG.md                              # Feature history & #BuildHybridAI sprint summary
```

---

## Demo Video & Hackathon Submission Checklist

- **Hackathon Track:** `#BuildHybridAI` — **Android (Gemini Nano via Firebase AI Logic)**
- **Demo Video (≤ 3 minutes, English, Public on YouTube):** `[Add your public YouTube demo link here]`
- **Public Repository URL:** `[Add your public GitHub/GitLab repository URL here]`
- **Demo Walkthrough Suggested Flow (under 3 min):**
  1. Show **Model Hub → Firebase AI Logic Hybrid SDK & System Health & Inference Dashboard** (switching `OnDeviceConfig` modes, AICore Gemini Nano status check, live thermal/battery gauge, and adaptive provider explanation).
  2. Run a multi-step prompt in **Chat Hub** (e.g., *"Create a 7:30 AM alarm and start the stopwatch"* or *"Open Chrome, search Android AI news, and take a screenshot"*) and show the **Tool Execution Log** confirming `Execution Verified` via `EventObserver`.
  3. Trigger a **Generative UI** control card (e.g., brightness/DND/budget chart) and show the live `EventObserver` confirmation in the dashboard.
  4. Drag the **Floating Lottie Assistant Orb** over another Android app to demonstrate persistent cross-app assistance.

---

## Documentation & Community

- [Apache 2.0 License](./LICENSE)
- [Security Policy](./SECURITY.md)
- [Contributing Guidelines](./CONTRIBUTING.md)
- [Code of Conduct](./CODE_OF_CONDUCT.md)
- [Changelog](./CHANGELOG.md)

---

## License

Copyright 2026 Mote Contributors. Licensed under the **Apache License, Version 2.0**. See [`LICENSE`](./LICENSE) for the complete license text.
