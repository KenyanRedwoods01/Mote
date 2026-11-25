# 01 — Splash Screen, Onboarding & Progressive Permissions

**Goal:** Get a first-time user from cold launch to their first working onscreen bot in **under 60 seconds**, while handling Gemini Nano 4 availability and Android's sensitive permissions transparently.

---

## 1. Splash & Warm-Up (`splash`)

* **Visual Layout:**
  * Full-bleed `#0B0D1D` (Mote Navy) background with the white centered **Mote** geometric `"M"` + floating dot mark (`mote-icon-white`).
  * Subtle pulse animation on the floating dot while initialization runs (target duration: 600–1,200 ms).
* **Background Initialization Sequence (Kotlin Coroutines):**
  1. Initialize Firebase (`Firebase.initialize(context)`) and install App Check (`DebugAppCheckProviderFactory` for hackathon debug builds; Play Integrity for release), per the [Firebase AI Logic Android Hybrid Guide](https://firebase.google.com/docs/ai-logic/hybrid/android/get-started).
  2. Fetch and activate **Firebase Remote Config** defaults (`hybrid_mode`, `cloud_model_name`, `max_local_tokens`).
  3. Call `FirebaseAIOnDevice.checkStatus()` asynchronously. If `OnDeviceModelStatus.AVAILABLE`, fire `onDeviceExtension?.warmUp()` in the background so first-token latency is near-instant.
* **Routing Exit:**
  * Not signed in → `auth`
  * Signed in, onboarding incomplete → `model_setup`
  * Returning user → `home`

---

## 2. Welcome & Sign-In (`auth`)

* **Visual Layout:**
  * Top third: White canvas (`#FFFFFF`) with Mote primary horizontal logo (`mote-primary`), concise value prop (*"Onscreen bots that control your phone privately—offline or online."*).
  * Middle: Three scannable capability rows with crisp monochrome icons:
    1. **Offline-first AI** — Runs privately on Gemini Nano 4 with automatic Gemini Flash fallback.
    2. **Instant Generative UI** — Spawns tailored sliders, toggles, and reply pills over any app.
    3. **You stay in control** — Sensitive apps are blocked; major actions require your confirmation.
  * Bottom CTAs:
    * Primary button (`#0B0D1D`): **Continue with Google** (Firebase Auth — syncs custom bots via Firestore).
    * Secondary text button: **Continue as Guest** (Firebase Anonymous Auth — zero-friction path for hackathon judges testing the APK!).

---

## 3. On-Device Model Setup (`model_setup`)

Handles all four real states returned by `FirebaseAIOnDevice.checkStatus()` ([Firebase AI Logic Docs](https://firebase.google.com/docs/ai-logic/hybrid/android/get-started)):

| `OnDeviceModelStatus` | UI State Shown | Primary Action |
|---|---|---|
| `AVAILABLE` | Emerald check badge: **"Gemini Nano 4 Ready (Offline & Free)"** | Auto-advances or **Continue** |
| `DOWNLOADABLE` | Explains AICore one-time download; shows **Download Gemini Nano 4** CTA + live `DownloadStatus.DownloadInProgress` byte progress bar | Download now (or **Skip & use Cloud Flash**) |
| `DOWNLOADING` | Determinate progress indicator + *"You can finish setup while AICore finishes downloading."* | **Continue to Permissions** |
| `UNAVAILABLE` | Cobalt info banner: **"This device doesn't support AICore on-device models yet—Mote will automatically use Cloud Gemini Flash via Firebase AI Logic."** | **Continue with Cloud Flash** |

---

## 4. Progressive Permission Wizard (`permissions`)

Android restricts overlay and accessibility permissions for good reason. Mote presents a **3-step transparent checklist** with clear plain-language explanations and never traps the user:

1. **Step 1 — Display Over Other Apps (`SYSTEM_ALERT_WINDOW`)** *(Required for floating bots)*
   * **Why we ask:** Lets your customizable Mote bots float at the edge of your screen.
   * **Action:** Opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for Mote's package; auto-detects grant in `onResume()`.
2. **Step 2 — Screen Context & Actions (`AccessibilityService`)** *(Optional; unlocks cross-app control)*
   * **Prominent Disclosure Dialog (Play Policy compliant):** Explicitly explains that Mote reads screen text *only* when you tap a bot, skips password fields and banking apps, and never uploads raw screens without local PII redaction.
   * **Action:** Opens `Settings.ACTION_ACCESSIBILITY_SETTINGS` + shows a 2-step visual hint (*Downloaded apps → Mote → Allow*).
   * **Skip behavior:** User can skip and still use Mote's in-app hub + Android Intents/Settings controls (brightness, timers, alarms).
3. **Step 3 — System Controls (`WRITE_SETTINGS` & Do Not Disturb Access)** *(Optional; unlocks Focus Mode)*
   * Grants direct native adjustment of screen brightness and Do Not Disturb without needing accessibility taps.

* **Completion CTA:** **"Spawn My First Bot"** → navigates to `home` and activates the default *Focus* floating bot.
