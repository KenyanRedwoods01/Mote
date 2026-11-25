# 04 — Phone Control, Action Confirmation & Security Center

**Goal:** Make Mote's phone control genuinely powerful while keeping the user 100% protected from unintended AI actions, prompt injection, and data leakage.

---

## 1. Three-Tier Action Safety Model

Every action returned inside a Generative UI JSON payload is validated against a strict Kotlin `sealed interface MoteAction` allowlist. Unrecognized actions are silently dropped.

| Safety Tier | Risk Level | Examples | Execution UX |
|---|---|---|---|
| **Tier 1 — Auto-Reversible** | Low | Adjust brightness, toggle DND, start timer/alarm, copy summary to clipboard, media play/pause | Executes immediately when user drags a slider or taps a chip; shows 4-second **Undo** snackbar |
| **Tier 2 — One-Tap Confirm** | Medium | Open another app, paste text into a search/message box, navigate to a specific system settings panel | Renders an explicit preview chip (*"Paste reply into WhatsApp input"*) requiring one user tap |
| **Tier 3 — Biometric / Hold Gate** | High | Tap **Send** on a message/email, delete items, change account settings, interact with payment/checkout screens | Shows a crimson-accented confirmation sheet detailing exact target app + node text; requires **BiometricPrompt** or **Hold-to-Confirm** |

---

## 2. Live Execution & Instant Kill-Switch HUD

When Mote executes a multi-step `AccessibilityService` sequence (e.g., opening a screen, pasting text, or navigating):
1. The Generative UI sheet collapses into a slim **Top-of-Screen Execution Pill** (`#0B0D1D` background, white text):
   * Left: Animated Mote dot + current step (*"Step 2 of 3: Inserting draft reply..."*).
   * Right: Red **`STOP`** button.
2. Tapping **`STOP`** immediately cancels the Kotlin `CoroutineScope`, halts `AccessibilityService` dispatch, and restores normal user control.
3. On completion, the pill flashes emerald (**"Done"**) for 1.5 seconds and dismisses.

---

## 3. Privacy & Security Center (`security`)

Accessible from the bottom navigation bar—designed to give users (and hackathon judges) full visibility into Mote's safeguards:

* **Card 1 — Hybrid Privacy Status:**
  * Shows active **Firebase App Check** attestation state (`Enforced — Debug/Play Integrity`) and confirms zero API keys are embedded in client code.
* **Card 2 — Sensitive App Blocklist (Always-On Defaults):**
  * Pre-populated toggle list that automatically blocks screen reading and auto-hides overlays on:
    * All `FLAG_SECURE` windows (non-disableable system rule)
    * Password fields (`AccessibilityNodeInfo.isPassword`)
    * Banking, crypto wallet, and 2FA authenticator apps (auto-detected + user-addable list)
* **Card 3 — Local PII Redaction Filter:**
  * Toggle (ON by default): Masks phone numbers, email addresses, OTP codes, and 16-digit card numbers **on-device in Kotlin** before any text ever leaves the phone for Cloud Gemini Flash.
* **Card 4 — Action Audit Log:**
  * Chronological list of every action Mote executed: timestamp, bot name, target app, action type, engine (`Local Nano 4` vs `Cloud Flash`), and whether the user confirmed it. Includes a one-tap **Clear Local Log** button.
