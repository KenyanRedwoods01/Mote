# Changelog

All notable changes to **Mote** are documented in this file.

## [1.0.0] — #BuildHybridAI Release

### Added
- **Firebase AI Logic Hybrid Inference SDK (`firebase-ai` + `firebase-ai-ondevice`)**:
  - Configured cached `GenerativeModel` instances with official `OnDeviceConfig` (`PREFER_ON_DEVICE`, `PREFER_IN_CLOUD`, `ONLY_IN_CLOUD`, `ONLY_ON_DEVICE`) and automatic on-device Gemini Nano ↔ Cloud Gemini fallback.
  - Added `AiCoreNanoManager` backed by ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`) for live Gemini Nano availability checks and on-device model downloads.
  - Added official native Firebase Function Calling (`FirebaseNativeToolDeclarations` using `Tool.functionDeclarations`) alongside dual-mode `IncrementalJsonStreamParser` streaming.
- **System Health & Inference Dashboard (`SystemHealthInferenceDashboard`)**:
  - Real-time visualization of Android battery temperature (°C), thermal headroom gauge with 40°C cloud-offload threshold, `PowerManager` thermal throttling status, battery/power draw, active hybrid inference mode, and adaptive switching rationale.
  - Live `ToolExecutionSync` `EventObserver` stream displaying verified OS callbacks.
- **`EventObserver` Pattern in `ToolExecutionSyncService`**:
  - Added `SystemActionEventObserver` / `AndroidSystemEventObserver` hooking into Android `BroadcastReceiver` (`AlarmManager`, `AudioManager` volume/ringer, `NotificationManager` DND), `ContentObserver` (`Settings.System.SCREEN_BRIGHTNESS`, `CalendarContract.Events`), and `ClipboardManager.OnPrimaryClipChangedListener` on a dedicated background `HandlerThread`.
  - Wired both multi-step AI tool chains and inline Generative UI actions through `EventObserver` confirmation before logging `Execution Verified`.
- **Sequential Tool Chain Orchestrator & `ToolChainState` Room Persistence**:
  - Natural-language-to-atomic-chain execution (`Take screenshot -> OCR -> Save to Notes`, `Open Chrome -> Search -> Screenshot`, `Phone Dial -> Live Cursor -> Voice Relay`, full Clock Alarm/Timer/Stopwatch suite, Calendar, Camera, Wi-Fi/Ping, Clipboard).
  - Chronological `ToolExecutionLog` UI with per-step Pending / Executing / Verified / Failed status badges and receipt IDs.
- **System-Wide Draggable Floating Lottie Overlay**:
  - Persistent cross-app floating orb hosted via `AccessibilityFloatingWindowManager` (`TYPE_ACCESSIBILITY_OVERLAY`) or `BotOverlayService` (`TYPE_APPLICATION_OVERLAY`) with touch-drag repositioning and `PrivacyGuard` auto-hide.
- **Open-Source Repository Documentation**:
  - Added `README.md`, Apache 2.0 `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, and `CHANGELOG.md`.

### Fixed & Optimized
- **AccessibilityService & Overlay Lifecycle Hardening**: Added `ViewTreeLifecycleOwner` / `ViewTreeViewModelStoreOwner` / `ViewTreeSavedStateRegistryOwner` on window root containers, exhaustive null checks and window-state event filtering in `MoteAccessibilityService.onAccessibilityEvent`, `Settings.canDrawOverlays` validation, and `START_NOT_STICKY` lifecycle guards to eliminate crash loops.
- **60/120fps UI Smoothness**: Paused idle floating orb & Lottie animations when not active/dragging/executing, deduplicated `BatteryResourceMonitor` and `SystemHealthManager` StateFlow emissions, and moved `ToolPermissionInspector` checks to `Dispatchers.IO`.
- **Responsive Material 3 Layout**: Fixed crushed vertical status pills in `FirebaseHybridStatusCard`, `ToolsAndPermissionsPanel`, and `IosTopNavBar`, and expanded bottom navigation scroll clearance across all tabs.
