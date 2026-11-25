# 02 — Home Hub & Customizable Bot Studio

**Goal:** Give the user a clean, fast command center inside the app to manage active onscreen bots, inspect hybrid AI status, and design new bot personas synced across devices via **Cloud Firestore**.

---

## 1. Home Hub (`home`)

* **Top App Bar:**
  * Left: Mote icon + **"Mote"** wordmark.
  * Right: Live Hybrid Status Pill:
    * Emerald dot + **`Nano 4 Ready`** when `AVAILABLE` (even in Airplane Mode).
    * Cobalt dot + **`Cloud Flash`** when falling back to cloud.
  * Gear icon → `settings`.
* **Section 1 — Active Onscreen Bots (Horizontal Strip):**
  * Displays each saved bot (*Focus*, *Reply*, *Saver*, etc.) with its custom vector avatar, name, accent color ring, and an instant **On/Off Switch** that spawns or dismisses that bot's `WindowManager` overlay immediately.
  * Tapping a bot card opens `bot_editor/{botId}`; tapping **`+ New Bot`** opens a blank `bot_editor/new`.
* **Section 2 — Quick Prompt Bar (In-App Foreground Testbed):**
  * Text field + mic button: *"Ask Mote to build a control card..."*
  * Quick suggestion chips: `Focus Mode`, `Summarize Clipboard`, `Battery Saver`, `Meeting Prep`.
  * Submitting renders the exact same **Generative UI Card** inline right below the bar—great for testing without even leaving the app.
* **Section 3 — Hybrid Telemetry Strip (Compact, Judge-Friendly):**
  * Three quiet numeric metrics (no clutter):
    * **Local Runs (Nano 4):** count + `% offline/private`
    * **Cloud Fallbacks (Flash):** count
    * **Avg Local Latency:** e.g. `420 ms`
* **Section 4 — Recent Action Log:**
  * Last 5 generated cards/actions with timestamp, bot used, engine badge (`Local Nano 4` or `Cloud Flash`), and a one-tap **Re-run** button.
* **Bottom Navigation Bar (3 destinations):**
  * `Home` (`home`) | `Bots` (`bot_studio`) | `Security` (`security`)

---

## 2. Bot Studio (`bot_studio` & `bot_editor/{botId}`)

Users can create **multiple specialized onscreen bots** that coexist cleanly on screen edges.

### Default Starter Bots (Seeded on First Launch)
| Bot Name | Default Accent | Primary Role | Default Hybrid Mode | Allowed Capabilities |
|---|---|---|---|---|
| **Focus** | `#059669` (Emerald) | Brightness, DND, focus timers, silencing apps | `PREFER_ON_DEVICE` | Settings API, Timers/Alarms |
| **Reply** | `#1D4ED8` (Cobalt) | Reads active chat screen, generates 3 quick-reply pills | `PREFER_ON_DEVICE` (Flash fallback for long threads) | Accessibility Read + Tier-3 Confirmed Tap |
| **Saver** | `#0B0D1D` (Navy) | Battery/data toggles, dark mode, quick cleanup card | `ONLY_ON_DEVICE` | Settings API, System Intents |

### Bot Editor Screen (`bot_editor/{botId}`)
1. **Live Interactive Preview (Top):**
   * Shows the bot avatar floating on a mini edge preview; tapping it previews its glow/expand animation in real time.
2. **Appearance Tab:**
   * **Name & Role Tag** (e.g., *Study Buddy*, *Shopper*).
   * **Avatar Shape:** Mote Orb, Geometric M, Minimal Spark, Pulse Ring, or Custom Emoji/Initial (rendered in native Jetpack Compose `Canvas` at 120 Hz—zero heavy assets).
   * **Color Swatch:** Mote Navy (`#0B0D1D`), Cobalt (`#1D4ED8`), Emerald (`#059669`), Amber (`#D97706`), Violet (`#7C3AED`).
   * **Size & Idle Opacity Sliders:** `44.dp–64.dp` diameter; `30%–100%` docked transparency so bots never distract.
   * **Edge Docking Behavior:** Snap to nearest edge / Lock left / Lock right / Hide during fullscreen video or games.
3. **Brain & Routing Tab:**
   * **System Prompt / Persona:** Editable instruction (e.g., *"Keep responses ultra-brief and prefer sliders and toggles."*).
   * **Hybrid Inference Policy:**
     * `Prefer On-Device (Nano 4 → Flash fallback)` *(Recommended)*
     * `Strictly On-Device (Offline & Private Only)`
     * `Prefer Cloud (Gemini Flash)`
4. **Permissions & Safety Tab:**
   * Per-bot toggles:
     * `[x]` System Settings & Intents (brightness, DND, timers)
     * `[ ]` Read Active Screen (AccessibilityService)
     * `[ ]` Perform Taps & Typing (requires confirmation gate)
   * **Save CTA:** Persists to local Room/DataStore immediately and syncs to **Cloud Firestore** (`users/{uid}/bots/{botId}`).
