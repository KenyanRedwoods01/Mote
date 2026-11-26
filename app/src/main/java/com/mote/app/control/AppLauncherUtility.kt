package com.mote.app.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.util.Locale

data class AppManifestEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val isRecentlyUsed: Boolean = false
)

/**
 * Queries Android's [PackageManager] for launchable activities to build a compact live
 * manifest of available apps for the AI, and handles intent-based launching & content sharing
 * (e.g., sharing a captured screenshot URI directly to WhatsApp, Telegram, Gmail, etc.).
 */
class AppLauncherUtility(context: Context) {

    private val appContext = context.applicationContext

    fun queryLaunchableManifest(recentPackages: Set<String> = emptySet()): List<AppManifestEntry> {
        val pm = appContext.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        return resolved.mapNotNull { info ->
            val ai = info.activityInfo ?: return@mapNotNull null
            if (ai.packageName == appContext.packageName) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { ai.packageName }
            AppManifestEntry(
                label = label,
                packageName = ai.packageName,
                activityName = ai.name,
                isRecentlyUsed = ai.packageName in recentPackages
            )
        }
            .distinctBy { it.packageName }
            .sortedWith(
                compareByDescending<AppManifestEntry> { it.isRecentlyUsed }
                    .thenBy { it.label.lowercase(Locale.US) }
            )
    }

    /**
     * Generates a compact manifest string injected into the AI's device context so the model
     * knows the exact installed apps available to launch on this phone.
     */
    fun buildCompactManifestForAi(
        recentPackages: Set<String> = emptySet(),
        maxApps: Int = 24
    ): String {
        val manifest = queryLaunchableManifest(recentPackages)
        if (manifest.isEmpty()) return "Installed Apps Manifest: (querying via common app intents)"
        val listed = manifest.take(maxApps).joinToString(", ") { entry ->
            if (entry.isRecentlyUsed) "${entry.label}*" else entry.label
        }
        val extra = if (manifest.size > maxApps) " (+${manifest.size - maxApps} more)" else ""
        return "Installed Launchable Apps Manifest (${manifest.size}): $listed$extra"
    }

    /**
     * Shares an image URI (such as a MediaProjection screenshot saved to MediaStore) and/or
     * text directly to a target Android app (e.g., WhatsApp, Telegram, Gmail, Messages) or
     * falls back cleanly to the Android system share sheet if the target app isn't installed.
     */
    fun shareToTargetApp(
        targetAppQuery: String?,
        mediaUriString: String?,
        textMessage: String?
    ): DeviceToolOutcome {
        val uri = mediaUriString?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val cleanText = textMessage?.trim()?.takeIf { it.isNotBlank() }
        if (uri == null && cleanText == null) {
            return DeviceToolOutcome(
                success = false,
                title = "Nothing to Share",
                summary = "No screenshot URI or message text was available to share.",
                badge = "MISSING CONTENT"
            )
        }

        val resolvedTarget = resolveSharePackage(targetAppQuery)
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (uri != null) "image/*" else "text/plain"
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (cleanText != null) {
                putExtra(Intent.EXTRA_TEXT, cleanText)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (resolvedTarget != null) {
            val (pkg, label) = resolvedTarget
            val directIntent = Intent(baseIntent).setPackage(pkg)
            try {
                appContext.startActivity(directIntent)
                return DeviceToolOutcome(
                    success = true,
                    title = "Shared to $label",
                    summary = buildString {
                        append("Sent ")
                        append(if (uri != null) "captured screenshot" else "message")
                        append(" directly to $label ($pkg).")
                    },
                    badge = "SHARED TO ${label.uppercase(Locale.US)}"
                )
            } catch (_: Exception) {
                // Target app didn't handle direct ACTION_SEND -> fall back to Android Share Sheet
            }
        }

        return try {
            val chooser = Intent.createChooser(baseIntent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
            val note = if (!targetAppQuery.isNullOrBlank()) {
                "$targetAppQuery is not installed; opened Android Share Sheet instead."
            } else {
                "Opened Android Share Sheet with your content ready."
            }
            DeviceToolOutcome(
                success = true,
                title = "Opened Android Share Sheet",
                summary = note,
                badge = "SHARE SHEET"
            )
        } catch (e: Exception) {
            DeviceToolOutcome(
                success = false,
                title = "Share Failed",
                summary = e.localizedMessage ?: "Could not open share handler.",
                badge = "SHARE FAILED"
            )
        }
    }

    private fun resolveSharePackage(query: String?): Pair<String, String>? {
        val clean = query?.trim()?.lowercase(Locale.US).orEmpty()
        if (clean.isBlank()) return null
        val pm = appContext.packageManager
        val knownShareTargets = listOf(
            Triple(listOf("whatsapp", "wa", "whats app"), listOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp"),
            Triple(listOf("telegram", "tg"), listOf("org.telegram.messenger"), "Telegram"),
            Triple(listOf("gmail", "email", "mail"), listOf("com.google.android.gm"), "Gmail"),
            Triple(listOf("messages", "sms", "text"), listOf("com.google.android.apps.messaging"), "Messages"),
            Triple(listOf("instagram", "insta", "ig"), listOf("com.instagram.android"), "Instagram"),
            Triple(listOf("discord"), listOf("com.discord"), "Discord"),
            Triple(listOf("slack"), listOf("com.Slack"), "Slack"),
            Triple(listOf("messenger"), listOf("com.facebook.orca"), "Messenger"),
            Triple(listOf("twitter", "x"), listOf("com.twitter.android"), "X / Twitter"),
            Triple(listOf("snapchat", "snap"), listOf("com.snapchat.android"), "Snapchat")
        )

        for ((aliases, pkgs, label) in knownShareTargets) {
            if (aliases.any { clean.contains(it) }) {
                for (pkg in pkgs) {
                    if (pm.getLaunchIntentForPackage(pkg) != null) {
                        return pkg to label
                    }
                }
            }
        }

        val installed = queryLaunchableManifest()
        val match = installed.firstOrNull {
            it.label.lowercase(Locale.US).contains(clean) || it.packageName.lowercase(Locale.US).contains(clean)
        }
        return match?.let { it.packageName to it.label }
    }
}
