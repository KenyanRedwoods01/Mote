# 03 — Floating Onscreen Bots & Generative UI Flow

**Goal:** Deliver a buttery-smooth 120 Hz floating bot experience over any Android app while working cleanly within Gemini Nano 4's **foreground-only** and **4,000-token** constraints ([Firebase AI Logic Android Hybrid Docs](https://firebase.google.com/docs/ai-logic/hybrid/android/get-started)).

---

## 1. Floating Bot Overlay State Machine (`BotOverlayService`)

Hosted in a Foreground Service with a low-priority persistent notification (*"Mote active — 2 bots ready"*), rendering each bot via a hardware-accelerated `ComposeView` attached to `WindowManager` (`TYPE_APPLICATION_OVERLAY`).

| State | Visual Behavior | Window Flags | CPU / Battery Impact |
|---|---|---|---|
| **Docked Idle** | Half-tucked pill or 48.dp orb on left/right screen edge at user's idle opacity (default 65%). | `FLAG_NOT_FOCUSABLE \| FLAG_LAYOUT_IN_SCREEN` | ~0% CPU; accessibility tree is **not** read |
| **Dragging** | Scales up to 1.08× with haptic tick; magnetic snap preview on left/right edge; bottom-center **× Dismiss** target appears. | `FLAG_NOT_FOCUSABLE` | 120 Hz spring animation (`Spring.DampingRatioMediumBouncy`) |
| **Multi-Bot Cluster** | When ≥ 2 bots are active, they can either dock independently or collapse into a single Mote badge that fans out on tap. | `FLAG_NOT_FOCUSABLE` | Minimal |
| **Auto-Hidden (Safe)** | Instantly sets `View.GONE` whenever a `FLAG_SECURE` window, system permission dialog, or blocklisted banking app enters foreground. | Hidden | Prevents tapjacking ([Android Tapjacking Docs](https://developer.android.com/privacy-and-security/risks/tapjacking)) |

---

## 2. The Foreground Scrim Handoff (`HybridInferenceActivity`)

Because AICore restricts on-device Gemini Nano inference to **foreground apps only**, Mote uses a clean two-step handoff when the user taps a floating bot over another app:

1. **Snapshot (before foreground switch, < 30 ms):**
   * If the bot has *Read Active Screen* enabled and the foreground package is not blocklisted, `AccessibilityService` traverses `rootInActiveWindow`, strips invisible nodes, redacts PII (emails, phone numbers, card patterns, `isPassword` fields), and builds a pruned JSON tree (~300–900 tokens).
2. **Launch Transparent Foreground Sheet (`HybridInferenceActivity`):**
   * Opens with `Theme.Translucent.NoTitleBar`, zero window-enter animation, and a dimmed 25% backdrop over the underlying app.
   * Because `HybridInferenceActivity` is now the foreground Android activity, **Gemini Nano 4 (`PREFER_ON_DEVICE`) runs legally and locally at full speed!**
   * The user sees a sleek bottom-anchored **Prompt & Generative UI Sheet** without ever feeling like they left their current app.

---

## 3. Prompt & Generative UI Card Sheet (`GenUiCardSheet`)

### Step A — Input Bar (Instant)
* Opens with the tapped bot's avatar, name, and 3 context-aware suggested chips (e.g., over a chat app: *"Draft 3 replies"*, *"Summarize thread"*; anywhere: *"Focus Mode"*, *"Save Battery"*).
* User taps a chip, types a short request, or taps the mic button.

### Step B — Hybrid Routing & Streaming State
* Shows a crisp animated shimmer card with a live routing pill:
  * **`Running on Gemini Nano 4...`** (emerald) when input < 4,000 tokens and Nano is available.
  * **`Escalating to Gemini Flash...`** (cobalt) if offline Nano is unavailable, returns `ErrorCode.BUSY`, or the request needs multi-step tool planning.

### Step C — Rendered Native Generative UI Card
Firebase AI Logic returns a strict `@Serializable` JSON payload via [structured output](https://firebase.google.com/docs/ai-logic/hybrid/android/generate-structured-output) (~120–220 tokens). Mote maps each widget directly to native Jetpack Compose components:

| Widget Type | JSON `type` | Native Compose Component | Example Use Case |
|---|---|---|---|
| **Slider** | `slider` | `Slider` + live numeric readout | Screen brightness (`25%`), media volume |
| **Toggle** | `toggle` | `Switch` + icon + status subtitle | Do Not Disturb, Dark Mode, Battery Saver |
| **Action Chip** | `action_chip` | `AssistChip` / `FilledTonalButton` | *"Start 25m Timer"*, *"Open Wi-Fi Panel"* |
| **Confirm Button** | `confirm_button` | High-contrast `#0B0D1D` CTA + Cancel | *"Send Reply"*, *"Apply All 3 Changes"* |
| **Quick Reply Pills** | `reply_pills` | Horizontal `FlowRow` of tappable drafts | 3 tone options (*Brief*, *Warm*, *Formal*) |
| **Progress / Info Card** | `info_card` | Compact summary + copy/share button | 3-bullet summary of a long screen |

* **Mandatory Transparency Header on Every Card:**
  * Left: Generated card title (e.g., **Focus Mode**).
  * Right: Engine provenance badge — emerald **`Local Nano 4`** or cobalt **`Cloud Flash`** + latency readout (e.g., `380 ms`).
* **Dismissal:** Swipe down or tap outside the sheet to return immediately to the underlying app; the bot smoothly re-docks on the screen edge.
