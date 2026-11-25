# 05 — Core User Journeys & Edge-Case Recovery

These four end-to-end journeys double as your **QA test plan** and your **3-minute YouTube demo script** for [#BuildHybridAI 2026](https://firebasehybridaihackathon.devpost.com/rules).

---

## Journey A — Offline Hero Flow (*"Focus Mode in Airplane Mode"*)
*Demo target: 0:30–1:20 in your YouTube video*

1. User pulls down Android Quick Settings and enables **Airplane Mode** (zero internet).
2. While browsing a distracting social feed, user taps the emerald **Focus** bot docked on the right edge.
3. `HybridInferenceActivity` opens cleanly over the feed; user taps **"Focus Mode"** (or says *"dim screen, DND on, 25 min timer"*).
4. Firebase AI Logic routes to **Gemini Nano 4** (`PREFER_ON_DEVICE`), returning a 140-token structured JSON payload in under 1 second.
5. `GenUiCardSheet` renders a native Compose card stamped with the emerald **`Local Nano 4`** badge:
   * Brightness slider set to `25%`
   * Do Not Disturb toggle set to `ON`
   * Action chip: `Start 25m Timer`
6. User taps **Apply All** → screen dims immediately, DND activates, and Android's native timer starts—all 100% offline.

---

## Journey B — Cross-App Cloud Fallback Flow (*"Smart Reply Over Long Chat"*)
*Demo target: 1:20–2:10 in your YouTube video*

1. User disables Airplane Mode and opens a long messaging thread.
2. User taps the cobalt **Reply** bot on the screen edge and taps **"Draft Replies"**.
3. `AccessibilityService` captures the visible conversation, redacts phone numbers/emails locally, and detects that the prompt + multi-step intent exceeds the lightweight local budget (or simulates a non-Nano device).
4. Firebase AI Logic seamlessly routes to **Gemini Flash** (`ONLY_IN_CLOUD` / automatic fallback), protected by **Firebase App Check**.
5. `GenUiCardSheet` renders with the cobalt **`Cloud Flash`** badge and 3 native **Quick Reply Pills** (*Brief*, *Friendly*, *Ask to reschedule*).
6. User taps *Friendly* → Tier-2 confirmation pastes the text into the reply box, and a Tier-3 confirmation gate asks before tapping **Send** (never sending silently!).

---

## Journey C — Multi-Bot Customization & Firestore Sync (*"Bot Studio"*)
*Demo target: 2:10–2:45 in your YouTube video*

1. User opens Mote → navigates to **Bot Studio** (`bot_studio`) → taps **`+ New Bot`**.
2. Names it **Saver**, picks the Geometric M avatar in `#0B0D1D` Navy, sets idle opacity to 50%, selects **`Strictly On-Device (Nano 4)`**, and enables only *System Settings & Intents* (no screen reading).
3. Taps **Save** → writes bot config to **Cloud Firestore** and immediately spawns the second floating bot onscreen.
4. User toggles the Remote Config debug switch or switches back to the home screen showing two distinct, customizable bots docked cleanly on the screen edge.

---

## Journey D — Edge Cases & Graceful Recovery

| Edge Case | Detection Signal | User-Facing UI Recovery |
|---|---|---|
| **AICore burst or battery quota hit** | `ErrorCode.BUSY` or `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` | Brief inline notice (*"On-device busy — switching to Cloud Flash"*) + exponential backoff retry or automatic Flash fallback |
| **Offline + Device without Gemini Nano** | `OnDeviceModelStatus.UNAVAILABLE` + no network | Clean empty state in sheet: *"Offline AI requires Gemini Nano on supported devices. Reconnect to use Cloud Flash, or use quick manual controls below."* (renders static fallback toggles for Brightness/DND!) |
| **AccessibilityService killed by OEM battery saver** | `AccessibilityManager` service check returns false | Amber banner on Home Hub & Bot Sheet: *"Screen control paused by Android — tap to re-enable in 2 taps."* |
| **Sensitive / `FLAG_SECURE` screen open** | Window flag or blocklisted package detected | Floating bots auto-hide; if triggered from Home Hub, toast explains: *"Mote pauses screen reading in protected apps for your privacy."* |
| **Prompt injection in scraped screen text** | Model outputs action outside `MoteAction` sealed allowlist or malformed JSON | Parser rejects invalid action cleanly; logs blocked attempt in Security Center; falls back to safe text-only summary card |
