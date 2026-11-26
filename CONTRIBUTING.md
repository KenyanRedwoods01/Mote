# Contributing to Mote

Thank you for your interest in contributing to **Mote**! We welcome bug reports, performance improvements, new verified Android tools, Generative UI components, and documentation enhancements.

---

## Code of Conduct

All contributors are expected to follow our [Code of Conduct](./CODE_OF_CONDUCT.md).

---

## Development Setup

1. **Fork & Clone** the repository.
2. Open the project in **Android Studio** (JDK 17, Android SDK 36).
3. Configure a Firebase development project with **Firebase AI Logic**, **App Check** (debug token registered), **Firebase Auth**, and **Cloud Firestore**, and place your `google-services.json` into `app/`.
4. Verify your local build:
   ```bash
   gradle :app:assembleDebug
   ```

---

## Architecture & Coding Guidelines

- **Language & UI:** 100% Kotlin + Jetpack Compose with Material Design 3 (`LocalMoteColors`, responsive spacing, minimum `48.dp` touch targets, `contentDescription` on icons/images, and `Modifier.testTag(...)` on interactive elements).
- **State Management:** MVVM using `ViewModel` + `StateFlow` / `collectAsStateWithLifecycle()`. Deduplicate high-frequency telemetry emissions to preserve 60/120fps scrolling.
- **Hybrid AI Routing:** Route primary AI inference through `HybridAiRouter` using Firebase AI Logic (`OnDeviceConfig` + `InferenceMode`). Never hardcode API keys or mock AI responses.
- **Tool Verification Rule:** Any new Android tool added to `GenUiToolRegistry` and `SequentialToolOrchestrator` **must** also implement post-execution verification in `ToolExecutionSyncService` (using `SystemActionEventObserver`, Room diffs, or OS state checks) so the UI never claims `Execution Verified` without evidence.
- **Google Play Policy Compliance:**
  - Never request broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`); use Android Photo Picker instead.
  - Keep phone interactions on `ACTION_DIAL` and network toggles on `Settings.Panel`.
  - Register `BroadcastReceiver`s with explicit `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` flags and unregister all observers cleanly in `shutdown()` / `onDestroy()`.
- **Accessibility & Overlay Safety:** Always attach `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner`, and `ViewTreeSavedStateRegistryOwner` to overlay window root containers, guard `onAccessibilityEvent` against null events and high-frequency scroll spam, and respect `PrivacyGuard`.

---

## Running Checks Before Submitting a PR

```bash
# 1. Verify Kotlin & Compose compilation
gradle :app:assembleDebug

# 2. Run unit / Robolectric tests
gradle :app:testDebugUnitTest

# 3. Validate Firestore security rules (if firestore.rules was modified)
node --test firestore.test.js
```

---

## Pull Request Process

1. Create a focused branch from `main` (e.g., `feat/calendar-observer` or `fix/overlay-drag-bounds`).
2. Keep PRs cohesive and modular (avoid monolithic files over ~500 lines where practical).
3. Include a concise description of **what changed**, **why**, and **how you verified it** (screenshots or screen recordings are appreciated for UI changes).
4. Ensure no private API keys, debug tokens, or personal data are included in your diff.

By submitting a contribution, you agree that your work will be licensed under the [Apache License 2.0](./LICENSE).
