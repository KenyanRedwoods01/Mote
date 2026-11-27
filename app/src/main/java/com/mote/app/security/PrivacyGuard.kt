package com.mote.app.security

import com.mote.app.ai.ActionSafetyTier
import com.mote.app.ai.MoteAction

object PrivacyGuard {

    private val blockedPackageKeywords = listOf(
        "bank", "wallet", "authenticator", "keepass", "1password", "bitwarden", "mpesa", "paypal", "stripe"
    )

    private val emailRegex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phoneRegex = Regex("""\+?\d[\d\s-]{7,14}\d""")
    private val cardRegex = Regex("""\b(?:\d[ -]*?){13,16}\b""")
    private val otpRegex = Regex("""\b\d{6}\b""")

    fun isPackageBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()
        return blockedPackageKeywords.any { lower.contains(it) }
    }

    fun redactPii(rawText: String): String {
        return rawText
            .replace(cardRegex, "[CARD_REDACTED]")
            .replace(emailRegex, "[EMAIL_REDACTED]")
            .replace(phoneRegex, "[PHONE_REDACTED]")
            .replace(otpRegex, "[OTP_REDACTED]")
    }

    fun classifyAction(action: MoteAction): ActionSafetyTier = when (action) {
        is MoteAction.SetBrightness,
        is MoteAction.SetVolume,
        is MoteAction.ToggleDnd,
        is MoteAction.StartTimer,
        is MoteAction.CopyText -> ActionSafetyTier.TIER_1_AUTO_REVERSIBLE
        is MoteAction.PasteReply -> ActionSafetyTier.TIER_2_ONE_TAP_CONFIRM
        is MoteAction.ConfirmSendMessage -> ActionSafetyTier.TIER_3_BIOMETRIC_CONFIRM
    }
}
