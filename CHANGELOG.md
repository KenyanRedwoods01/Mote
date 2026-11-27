# Changelog

All notable changes to **Mote** ([`git@github.com:KenyanRedwoods01/Mote.git`](https://github.com/KenyanRedwoods01/Mote)) are documented in this file.

## [1.0.0] — #BuildHybridAI Release

### Added
- **Firebase AI Logic Hybrid Inference SDK (`firebase-ai` + `firebase-ai-ondevice`)**:
  - Configured cached `GenerativeModel` instances with official `OnDeviceConfig` (`PREFER_ON_DEVICE`, `PREFER_IN_CLOUD`, `ONLY_IN_CLOUD`, `ONLY_ON_DEVICE`) and automatic on-device Gemini Nano ↔ Cloud Gemini fallback.
  - Added `AiCoreNanoManager` backed by ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`) for live Gemini Nano availability checks and on-device model downloads.
  - Added official native Firebase Function Calling (`FirebaseNativeToolDeclarations` using `Tool.functionDeclarations`) alongside dual-mode `IncrementalJsonStreamParser` streaming.
- **Dedicated System Health & Inference Dashboard (`SystemHealthInferenceScreen` + `SystemHealthInferenceDashboard`)**:
  - Real-time visualization of Android battery temperature (°C), thermal headroom gauge with 40°C cloud-offload threshold, `PowerManager` thermal throttling status, battery/power draw, active hybrid inference mode (On-Device vs Cloud), and adaptive switching rationale.
  - Live `ToolExecutionSync` `EventObserver` stream displaying verified OS callbacks.
- **`EventObserver` Pattern in `ToolExecutionSyncService` + `AudioVolumeControlTool`**:
  - Added `SystemActionEventObserver` / `AndroidSystemEventObserver` hooking into Android `BroadcastReceiver` (`AlarmManager`, `AudioManager` volume/ringer, `NotificationManager` DND), `ContentObserver` (`Settings.System.SCREEN_BRIGHTNESS`, `CalendarContract.Events`), and `ClipboardManager.OnPrimaryClipChangedListener` on a dedicated background `HandlerThread`.
  - Added first-class Android `AudioManager` volume & ringer control (`setVolume`, `adjustVolume`, `setRingerMode`, `getVolumeStatus`) and wired both multi-step AI tool chains and inline Generative UI actions through `EventObserver` confirmation before logging `Execution Verified`.
- **Sequential Tool Chain Orchestrator & `ToolChainState` Room Persistence**:
  - Natural-language-to-atomic-chain execution (`Take screenshot -> OCR -> Save to Notes`, `Open Chrome -> Search -> Screenshot`, `Phone Dial -> Live Cursor -> Voice Relay`, full Clock Alarm/Timer/Stopwatch suite, Audio/Volume, Calendar, Camera, Wi-Fi/Ping, Clipboard).
  - Chronological `ToolExecutionLog` UI with per-step Pending / Executing / Verified / Failed status badges and receipt IDs.
- **System-Wide Draggable Floating Lottie Overlay**:
  - Persistent cross-app floating orb hosted via `AccessibilityFloatingWindowManager` (`TYPE_ACCESSIBILITY_OVERLAY`) or `BotOverlayService` (`TYPE_APPLICATION_OVERLAY`) with touch-drag repositioning and `PrivacyGuard` auto-hide (with redundant in-app orb removed for a cleaner chat viewport).
- **Open-Source Repository Documentation**:
  - Added `README.md`, Apache 2.0 `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, and `CHANGELOG.md`.

### Fixed & Optimized
- **Clock Tools (Alarms, Countdown Timers, Stopwatch)**: Fixed float-to-int JSON argument decoding from Firebase Native Function Calling (`7.0` -> `7`), normalized `clock_control_timer` verification operations, fixed `verifyStopwatchSync` polling window, and wired GenUI timer buttons directly into `ClockToolsManager`.
- **Live Cursor Click Execution**: Fixed `MoteAccessibilityService` tap execution by targeting non-Mote foreground `TYPE_APPLICATION` windows, temporarily suspending the full-screen `SystemCursorOverlayManager` window during synthetic `dispatchGesture` calls on Android 12–16, and dispatching gestures on the main `Handler`.
- **Zero Raw JSON Leakage in Chat UI**: Fixed `HybridAiRouter` native function call delta emissions, upgraded `IncrementalJsonStreamParser` and `ChatGenUiToolProtocol` to intercept bare/fenced GenUI specs (`{"title":...,"widgets":[...]}`) and alternate tool schemas, and added defensive inline GenUI rendering in `ChatHubScreen`.
- **AccessibilityService & Overlay Lifecycle Hardening**: Added `ViewTreeLifecycleOwner` / `ViewTreeViewModelStoreOwner` / `ViewTreeSavedStateRegistryOwner` on window root containers, exhaustive null checks and window-state event filtering in `MoteAccessibilityService.onAccessibilityEvent`, `Settings.canDrawOverlays` validation, and `START_NOT_STICKY` lifecycle guards to eliminate crash loops.
- **60/120fps UI Smoothness & Responsive M3 Layout**: Paused idle floating orb & Lottie animations when not active/dragging/executing, deduplicated `BatteryResourceMonitor` and `SystemHealthManager` StateFlow emissions, fixed crushed status badges, and expanded bottom navigation clearance across all tabs.
