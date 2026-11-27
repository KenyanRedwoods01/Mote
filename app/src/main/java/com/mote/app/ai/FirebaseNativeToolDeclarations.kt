package com.mote.app.ai

import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Official Firebase AI Logic Native Function Calling declarations (`Tool.functionDeclarations`)
 * mapped directly to Mote's verified Android device & clock tools.
 *
 * Works in dual-mode alongside `<<<TOOL_CALL>>>` streamed JSON tags so cloud Gemini models can
 * invoke native `FunctionCallPart`s while on-device LiteRT-LM models continue using structured tags,
 * both flowing into [SequentialToolOrchestrator] and [com.mote.app.control.ToolExecutionSyncService].
 *
 * Note: Every declaration supplies at least one optional parameter (never an empty `OBJECT`
 * property map) to ensure 100% schema compatibility across all Gemini API endpoints.
 */
object FirebaseNativeToolDeclarations {

    private val serializerJson = Json { encodeDefaults = false }

    fun buildTools(enabledToolIds: Set<String>): List<Tool> {
        val declarations = mutableListOf<FunctionDeclaration>()

        if (GenUiToolRegistry.TOOL_ALARM_MANAGER in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "addAlarm",
                description = "Schedule a new Android alarm via AlarmManager at a specific hour/minute, time string (e.g. '7:30 AM'), or relative offset in minutes.",
                parameters = mapOf(
                    "hour" to Schema.integer("24-hour clock hour (0-23)"),
                    "minute" to Schema.integer("Minute of hour (0-59)"),
                    "time" to Schema.string("Optional flexible time string like '7:30 AM' or 'in 15 minutes'"),
                    "inMinutes" to Schema.integer("Relative offset in minutes from now"),
                    "label" to Schema.string("Short alarm label"),
                    "days" to Schema.array(Schema.string(), "Optional recurring weekday labels e.g. Mon, Tue"),
                    "enabled" to Schema.boolean("Whether alarm is enabled immediately")
                ),
                optionalParameters = listOf("hour", "minute", "time", "inMinutes", "label", "days", "enabled")
            )
            declarations += FunctionDeclaration(
                name = "updateAlarm",
                description = "Update an existing alarm's time, label, or enabled state.",
                parameters = mapOf(
                    "query" to Schema.string("Existing alarm label, time, or ID"),
                    "hour" to Schema.integer("New 24-hour hour (0-23)"),
                    "minute" to Schema.integer("New minute (0-59)"),
                    "time" to Schema.string("Optional new time string like '8:15 AM'"),
                    "newLabel" to Schema.string("Updated alarm label"),
                    "enabled" to Schema.boolean("Updated enabled status")
                ),
                optionalParameters = listOf("query", "hour", "minute", "time", "newLabel", "enabled")
            )
            declarations += FunctionDeclaration(
                name = "toggleAlarm",
                description = "Enable, disable, or flip a matching alarm or 'all' alarms.",
                parameters = mapOf(
                    "query" to Schema.string("Alarm label, time, ID, or 'all'"),
                    "enabled" to Schema.boolean("True to turn on, false to turn off; omit to toggle")
                ),
                optionalParameters = listOf("query", "enabled")
            )
            declarations += FunctionDeclaration(
                name = "snoozeAlarm",
                description = "Snooze or shift forward an existing alarm by a number of minutes (default 10).",
                parameters = mapOf(
                    "query" to Schema.string("Alarm label, time, or ID"),
                    "minutes" to Schema.integer("Minutes to snooze/shift forward (1-180)")
                ),
                optionalParameters = listOf("query", "minutes")
            )
            declarations += FunctionDeclaration(
                name = "deleteAlarm",
                description = "Delete a matching alarm or 'all' alarms from AlarmManager.",
                parameters = mapOf(
                    "query" to Schema.string("Alarm label, time, ID, or 'all'")
                ),
                optionalParameters = listOf("query")
            )
            declarations += FunctionDeclaration(
                name = "listAlarms",
                description = "List all currently scheduled alarms.",
                parameters = mapOf(
                    "filter" to Schema.string("Optional label or status filter")
                ),
                optionalParameters = listOf("filter")
            )
        }

        if (GenUiToolRegistry.TOOL_COUNTDOWN_TIMER in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "startTimer",
                description = "Start a live ticking countdown timer using durationSeconds, minutes/seconds, or duration string.",
                parameters = mapOf(
                    "durationSeconds" to Schema.integer("Total duration in seconds (1-86400)"),
                    "minutes" to Schema.integer("Optional duration in minutes"),
                    "seconds" to Schema.integer("Optional extra seconds"),
                    "duration" to Schema.string("Optional flexible duration string like '5m 30s'"),
                    "label" to Schema.string("Optional timer label")
                ),
                optionalParameters = listOf("durationSeconds", "minutes", "seconds", "duration", "label")
            )
            declarations += FunctionDeclaration(
                name = "clock_control_timer",
                description = "Control an active countdown timer: pause, resume, reset, delete, or status.",
                parameters = mapOf(
                    "action" to Schema.enumeration(listOf("pause", "resume", "reset", "delete", "status"), "Timer action"),
                    "query" to Schema.string("Optional timer label or ID")
                ),
                optionalParameters = listOf("query")
            )
        }

        if (GenUiToolRegistry.TOOL_STOPWATCH in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "clock_stopwatch",
                description = "Control the live centisecond stopwatch: start, pause, resume, lap, clearLaps, listLaps, reset, or status.",
                parameters = mapOf(
                    "action" to Schema.enumeration(
                        listOf("start", "pause", "resume", "lap", "clearLaps", "listLaps", "reset", "status"),
                        "Stopwatch operation"
                    )
                )
            )
        }

        if (GenUiToolRegistry.TOOL_CALENDAR in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "addEvent",
                description = "Create a calendar event and sync to Android Calendar.",
                parameters = mapOf(
                    "title" to Schema.string("Event title"),
                    "date" to Schema.string("ISO date YYYY-MM-DD or 'today'/'tomorrow'"),
                    "startTime" to Schema.string("Start time e.g. 14:00"),
                    "endTime" to Schema.string("End time e.g. 15:00"),
                    "location" to Schema.string("Optional location"),
                    "notes" to Schema.string("Optional event notes")
                ),
                optionalParameters = listOf("date", "startTime", "endTime", "location", "notes")
            )
            declarations += FunctionDeclaration(
                name = "updateEvent",
                description = "Update an existing calendar event's title, date, time, or location.",
                parameters = mapOf(
                    "query" to Schema.string("Existing event title or ID"),
                    "newTitle" to Schema.string("Updated event title"),
                    "date" to Schema.string("New ISO date YYYY-MM-DD"),
                    "startTime" to Schema.string("New start time e.g. 15:00"),
                    "endTime" to Schema.string("New end time e.g. 16:00"),
                    "location" to Schema.string("Updated location")
                ),
                optionalParameters = listOf("query", "newTitle", "date", "startTime", "endTime", "location")
            )
            declarations += FunctionDeclaration(
                name = "deleteEvent",
                description = "Delete a matching calendar event or 'all' events.",
                parameters = mapOf(
                    "query" to Schema.string("Event title, ID, or 'all'")
                ),
                optionalParameters = listOf("query")
            )
            declarations += FunctionDeclaration(
                name = "listEvents",
                description = "List saved calendar events.",
                parameters = mapOf(
                    "date" to Schema.string("Optional ISO date filter YYYY-MM-DD or 'today'")
                ),
                optionalParameters = listOf("date")
            )
        }

        if (GenUiToolRegistry.TOOL_CAMERA in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "openCamera",
                description = "Open the Android Camera in photo, selfie, or video mode with optional timer.",
                parameters = mapOf(
                    "mode" to Schema.enumeration(listOf("photo", "selfie", "video"), "Capture mode"),
                    "facing" to Schema.enumeration(listOf("back", "front"), "Camera lens facing"),
                    "timerSeconds" to Schema.integer("Optional countdown timer seconds")
                ),
                optionalParameters = listOf("mode", "facing", "timerSeconds")
            )
            declarations += FunctionDeclaration(
                name = "pressCameraShutter",
                description = "Use Accessibility live cursor to hover and click the active Camera shutter button.",
                parameters = mapOf(
                    "mode" to Schema.string("Optional shutter context e.g. photo or video")
                ),
                optionalParameters = listOf("mode")
            )
            declarations += FunctionDeclaration(
                name = "inspectCamera",
                description = "Inspect physical Camera2 lenses, flash availability, and sensor specs.",
                parameters = mapOf(
                    "facing" to Schema.string("Optional lens filter: front or back")
                ),
                optionalParameters = listOf("facing")
            )
            declarations += FunctionDeclaration(
                name = "toggleFlashlight",
                description = "Turn the Android Camera2 flashlight torch on or off.",
                parameters = mapOf(
                    "enabled" to Schema.boolean("True for ON, false for OFF; omit to toggle")
                ),
                optionalParameters = listOf("enabled")
            )
        }

        if (GenUiToolRegistry.TOOL_SCREEN_CAPTURE in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "takeScreenshot",
                description = "Capture the current Android screen via MediaProjection or Accessibility.",
                parameters = mapOf(
                    "label" to Schema.string("Optional capture label"),
                    "analyze" to Schema.boolean("True to automatically run visual analysis after capture")
                ),
                optionalParameters = listOf("label", "analyze")
            )
            declarations += FunctionDeclaration(
                name = "ocrAnalysis",
                description = "Run Vision OCR text extraction on the latest captured screenshot.",
                parameters = mapOf(
                    "captureId" to Schema.string("Optional captureId or \$lastCaptureId")
                ),
                optionalParameters = listOf("captureId")
            )
        }

        if (GenUiToolRegistry.TOOL_NOTES in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "saveNote",
                description = "Save a durable note into Room storage.",
                parameters = mapOf(
                    "title" to Schema.string("Note title"),
                    "content" to Schema.string("Note body text or \$lastExtractedText / \$lastClipboardText")
                ),
                optionalParameters = listOf("title")
            )
            declarations += FunctionDeclaration(
                name = "listNotes",
                description = "List all saved notes.",
                parameters = mapOf(
                    "filter" to Schema.string("Optional search keyword")
                ),
                optionalParameters = listOf("filter")
            )
            declarations += FunctionDeclaration(
                name = "deleteNote",
                description = "Delete a saved note by title, ID, or 'all'.",
                parameters = mapOf(
                    "query" to Schema.string("Note title, ID, or 'all'")
                ),
                optionalParameters = listOf("query")
            )
        }

        if (GenUiToolRegistry.TOOL_VOICE_CALL in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "dialPhone",
                description = "Open the Android Phone dialer pre-filled with a phone number.",
                parameters = mapOf(
                    "phoneNumber" to Schema.string("Phone number to dial"),
                    "contactLabel" to Schema.string("Optional contact display label")
                ),
                optionalParameters = listOf("contactLabel")
            )
            declarations += FunctionDeclaration(
                name = "clickCallButton",
                description = "Use Accessibility live cursor to hover and click the Dialer Call button.",
                parameters = mapOf(
                    "phoneNumber" to Schema.string("Optional phone number context")
                ),
                optionalParameters = listOf("phoneNumber")
            )
            declarations += FunctionDeclaration(
                name = "sendSms",
                description = "Open Android SMS composer pre-filled with recipient and message.",
                parameters = mapOf(
                    "phoneNumber" to Schema.string("Recipient phone number"),
                    "message" to Schema.string("SMS message body")
                ),
                optionalParameters = listOf("message")
            )
            declarations += FunctionDeclaration(
                name = "startVoiceCallRelay",
                description = "Start the real-time Android SpeechRecognizer STT <-> Gemini <-> TTS live voice relay.",
                parameters = mapOf(
                    "targetLabel" to Schema.string("Call label"),
                    "openingGreeting" to Schema.string("Initial spoken greeting")
                ),
                optionalParameters = listOf("targetLabel", "openingGreeting")
            )
            declarations += FunctionDeclaration(
                name = "stopVoiceCallRelay",
                description = "Stop the active real-time STT/TTS live voice relay.",
                parameters = mapOf(
                    "reason" to Schema.string("Optional stop reason")
                ),
                optionalParameters = listOf("reason")
            )
            declarations += FunctionDeclaration(
                name = "speakText",
                description = "Speak text aloud using Android Text-to-Speech.",
                parameters = mapOf(
                    "text" to Schema.string("Text to speak aloud")
                )
            )
        }

        if (GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "openApp",
                description = "Launch an installed Android app by name (e.g. Chrome, YouTube, WhatsApp, Spotify, Maps).",
                parameters = mapOf(
                    "appName" to Schema.string("App name to open")
                )
            )
            declarations += FunctionDeclaration(
                name = "searchInApp",
                description = "Open an app (Chrome, YouTube, Maps, Spotify, Play Store) directly to search results.",
                parameters = mapOf(
                    "appName" to Schema.string("Target app name such as Chrome or YouTube"),
                    "query" to Schema.string("Search query text")
                )
            )
            declarations += FunctionDeclaration(
                name = "shareToApp",
                description = "Share text or a captured screenshot URI to an app such as WhatsApp, Telegram, or Gmail.",
                parameters = mapOf(
                    "appName" to Schema.string("Optional target app name"),
                    "text" to Schema.string("Optional message or \$lastExtractedText"),
                    "mediaUri" to Schema.string("Optional screenshot URI or \$lastSavedUri")
                ),
                optionalParameters = listOf("appName", "text", "mediaUri")
            )
            declarations += FunctionDeclaration(
                name = "getRecentApps",
                description = "List recently used Android applications via UsageStatsManager.",
                parameters = mapOf(
                    "limit" to Schema.integer("Max number of recent apps to return (default 8)")
                ),
                optionalParameters = listOf("limit")
            )
            declarations += FunctionDeclaration(
                name = "listInstalledApps",
                description = "List installed launchable apps on this Android device.",
                parameters = mapOf(
                    "query" to Schema.string("Optional app name filter")
                ),
                optionalParameters = listOf("query")
            )
        }

        if (GenUiToolRegistry.TOOL_NAVIGATION in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "navigateMaps",
                description = "Launch Google Maps turn-by-turn navigation to a destination.",
                parameters = mapOf(
                    "destination" to Schema.string("Address or place name"),
                    "mode" to Schema.enumeration(listOf("driving", "walking", "bicycling", "transit"), "Travel mode")
                ),
                optionalParameters = listOf("mode")
            )
            declarations += FunctionDeclaration(
                name = "openUrl",
                description = "Open a web URL in the Android browser.",
                parameters = mapOf(
                    "url" to Schema.string("Full or domain URL to open")
                )
            )
            declarations += FunctionDeclaration(
                name = "openSettings",
                description = "Open a specific Android Settings screen (wifi, bluetooth, display, sound, battery, apps, main).",
                parameters = mapOf(
                    "section" to Schema.string("Settings section name")
                ),
                optionalParameters = listOf("section")
            )
            declarations += FunctionDeclaration(
                name = "navigateSystem",
                description = "Perform an Android system global navigation action via AccessibilityService.",
                parameters = mapOf(
                    "action" to Schema.enumeration(
                        listOf("back", "home", "recents", "notifications", "quick_settings", "split_screen"),
                        "System navigation action"
                    )
                )
            )
        }

        if (GenUiToolRegistry.TOOL_CLIPBOARD in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "readClipboard",
                description = "Read current text from the Android system clipboard.",
                parameters = mapOf(
                    "label" to Schema.string("Optional context label")
                ),
                optionalParameters = listOf("label")
            )
            declarations += FunctionDeclaration(
                name = "writeClipboard",
                description = "Write text into the Android system clipboard.",
                parameters = mapOf(
                    "text" to Schema.string("Text to copy into clipboard"),
                    "label" to Schema.string("Optional clip label")
                ),
                optionalParameters = listOf("label")
            )
            declarations += FunctionDeclaration(
                name = "clearClipboard",
                description = "Clear the current Android system clipboard contents.",
                parameters = mapOf(
                    "confirm" to Schema.boolean("Optional confirmation flag")
                ),
                optionalParameters = listOf("confirm")
            )
            declarations += FunctionDeclaration(
                name = "pasteToScreen",
                description = "Paste clipboard text or provided text into the currently focused input field via Accessibility.",
                parameters = mapOf(
                    "text" to Schema.string("Optional text override; omit to paste current clipboard")
                ),
                optionalParameters = listOf("text")
            )
        }

        if (GenUiToolRegistry.TOOL_NETWORK in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "getNetworkStatus",
                description = "Inspect live Wi-Fi, cellular, signal bars, IP, and connectivity status.",
                parameters = mapOf(
                    "detail" to Schema.string("Optional scope: summary or full")
                ),
                optionalParameters = listOf("detail")
            )
            declarations += FunctionDeclaration(
                name = "testConnectivity",
                description = "Run a live TCP/DNS reachability latency ping check.",
                parameters = mapOf(
                    "host" to Schema.string("Optional target host (default 8.8.8.8)")
                ),
                optionalParameters = listOf("host")
            )
            declarations += FunctionDeclaration(
                name = "openWifiPanel",
                description = "Open Android's native Wi-Fi Settings Panel.",
                parameters = mapOf(
                    "section" to Schema.string("Optional panel hint")
                ),
                optionalParameters = listOf("section")
            )
            declarations += FunctionDeclaration(
                name = "openInternetPanel",
                description = "Open Android's native Internet Connectivity Quick Panel (Wi-Fi + Mobile Data).",
                parameters = mapOf(
                    "section" to Schema.string("Optional panel hint")
                ),
                optionalParameters = listOf("section")
            )
            declarations += FunctionDeclaration(
                name = "openNetworkSettings",
                description = "Open Android network settings for wifi, mobile, airplane, or vpn.",
                parameters = mapOf(
                    "section" to Schema.enumeration(listOf("wifi", "mobile", "airplane", "vpn"), "Network settings section")
                ),
                optionalParameters = listOf("section")
            )
        }

        if (GenUiToolRegistry.TOOL_AUDIO_VOLUME in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "setVolume",
                description = "Set Android AudioManager stream volume percentage (0-100) for media, ring, alarm, or notification.",
                parameters = mapOf(
                    "percent" to Schema.integer("Target volume percentage (0-100)"),
                    "stream" to Schema.enumeration(listOf("media", "ring", "alarm", "notification", "voice"), "Audio stream")
                ),
                optionalParameters = listOf("stream")
            )
            declarations += FunctionDeclaration(
                name = "adjustVolume",
                description = "Raise, lower, mute, or unmute Android volume.",
                parameters = mapOf(
                    "direction" to Schema.enumeration(listOf("up", "down", "mute", "unmute"), "Adjustment direction"),
                    "stream" to Schema.enumeration(listOf("media", "ring", "alarm", "notification"), "Audio stream")
                ),
                optionalParameters = listOf("stream")
            )
            declarations += FunctionDeclaration(
                name = "setRingerMode",
                description = "Switch Android ringer mode between normal, vibrate, and silent.",
                parameters = mapOf(
                    "mode" to Schema.enumeration(listOf("normal", "vibrate", "silent"), "Target ringer mode")
                )
            )
            declarations += FunctionDeclaration(
                name = "getVolumeStatus",
                description = "Read current media, ring, and alarm volume percentages and active ringer mode.",
                parameters = mapOf(
                    "stream" to Schema.string("Optional stream filter")
                ),
                optionalParameters = listOf("stream")
            )
        }

        if (GenUiToolRegistry.TOOL_APP_CONTROL in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "hoverAndClick",
                description = "Glide Mote's live floating cursor over a visible UI button/element and click or hover it.",
                parameters = mapOf(
                    "target" to Schema.string("Visible text or label of UI control"),
                    "hoverOnly" to Schema.boolean("True to only hover without clicking")
                ),
                optionalParameters = listOf("hoverOnly")
            )
            declarations += FunctionDeclaration(
                name = "hoverTapCoordinates",
                description = "Glide Mote's live floating cursor to normalized (0..1) or pixel coordinates and tap.",
                parameters = mapOf(
                    "x" to Schema.double("Horizontal coordinate (0.0-1.0 normalized or px)"),
                    "y" to Schema.double("Vertical coordinate (0.0-1.0 normalized or px)"),
                    "label" to Schema.string("Optional visual label"),
                    "longPress" to Schema.boolean("True for long press"),
                    "hoverOnly" to Schema.boolean("True to hover only")
                ),
                optionalParameters = listOf("label", "longPress", "hoverOnly")
            )
            declarations += FunctionDeclaration(
                name = "swipeScreen",
                description = "Perform a real directional scroll/swipe gesture with visual cursor tracking.",
                parameters = mapOf(
                    "direction" to Schema.enumeration(listOf("up", "down", "left", "right"), "Swipe direction")
                )
            )
            declarations += FunctionDeclaration(
                name = "typeInField",
                description = "Hover over an editable text input field and enter text via Accessibility.",
                parameters = mapOf(
                    "text" to Schema.string("Text to type into the field"),
                    "target" to Schema.string("Optional field label or hint")
                ),
                optionalParameters = listOf("target")
            )
            declarations += FunctionDeclaration(
                name = "inspectScreenElements",
                description = "List visible clickable and editable controls on the foreground app screen.",
                parameters = mapOf(
                    "filter" to Schema.string("Optional element label filter")
                ),
                optionalParameters = listOf("filter")
            )
        }

        if (declarations.isEmpty()) return emptyList()
        return listOf(Tool.functionDeclarations(declarations))
    }

    /**
     * Converts a native Firebase AI Logic [FunctionCallPart] into Mote's canonical
     * [ChatToolInvocation] JSON string so it plugs directly into [SequentialToolOrchestrator].
     */
    fun toCanonicalToolJson(call: FunctionCallPart): String {
        val argsObj = JsonObject(call.args)
        val wrapper = buildJsonObject {
            put("tool", JsonPrimitive(call.name))
            put("args", argsObj)
        }
        return serializerJson.encodeToString(JsonObject.serializer(), wrapper)
    }

    fun toInvocation(call: FunctionCallPart): ChatToolInvocation {
        return ChatToolInvocation(
            tool = call.name,
            args = JsonObject(call.args)
        )
    }
}
