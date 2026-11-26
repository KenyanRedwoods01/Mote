# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| `1.0.x` (main) | Yes |

---

## Security Architecture

Mote interacts with sensitive Android capabilities (Accessibility, Screen Capture, Calendar, Clipboard, and Cloud AI) and enforces defense-in-depth protections throughout the stack:

1. **Firebase App Check Enforcement (`app/src/main/java/com/mote/app/security/AppCheckInit.kt`)**
   - All Firebase AI Logic requests are protected by Firebase App Check.
   - Debug builds use `DebugAppCheckProviderFactory` (tokens must never be committed to Git).
   - Release builds use `RecaptchaAppCheckProviderFactory`.
2. **Zero-Trust Firestore Security Rules (`firestore.rules`)**
   - Authenticated reads and writes are strictly scoped to `request.auth.uid == userId`.
   - Anonymous authentication is disabled; cloud sync requires Google Sign-In via Jetpack `CredentialManager`.
   - Verified via automated rules tests in `firestore.test.js`.
3. **PrivacyGuard Screen & PII Protection (`app/src/main/java/com/mote/app/security/PrivacyGuard.kt`)**
   - Automatically hides the floating overlay and blocks screen reading on banking, finance, password-manager, and authentication packages.
   - Skips `AccessibilityNodeInfo.isPassword` fields entirely during UI traversal.
   - Redacts emails, phone numbers, card-like digits, and tokens before passing screen context to any AI model.
4. **Explicit Tool Authorization & Truth-in-Execution**
   - Users control every AI tool category individually inside **Tools & Permissions**. Disabling a tool strips it from model prompts and blocks execution at runtime.
   - High-impact actions (such as sending messages) require explicit confirmation and never run silently.
   - `ToolExecutionSyncService` verifies actual OS state via `BroadcastReceiver` and `ContentObserver` before marking actions verified.
5. **Secrets & Key Management**
   - Never hardcode Gemini API keys, OAuth client secrets, signing keystores, or App Check debug tokens in Kotlin source files or commits.
   - Do not commit real production `google-services.json` files or release keystores to public forks if they contain private credentials.

---

## Reporting a Vulnerability

If you discover a security vulnerability in Mote, please report it responsibly rather than opening a public GitHub issue:

1. Use GitHub **Private Vulnerability Reporting** (Security Advisories) on this repository, or email the maintainers directly with `[SECURITY] Mote:` in the subject line.
2. Include:
   - Affected Android version, device/emulator model, and app build (`debug`/`release`).
   - Clear reproduction steps or a minimal proof-of-concept.
   - Potential security or privacy impact.
3. We will acknowledge receipt within **48 hours** and aim to provide a patch or mitigation plan within **7 business days**.
