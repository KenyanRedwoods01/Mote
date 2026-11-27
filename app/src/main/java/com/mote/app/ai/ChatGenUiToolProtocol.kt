package com.mote.app.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Interactive UI parts attached inline to a streaming or completed Chat message
 * (Vercel AI SDK Generative User Interfaces architecture for Android Compose).
 */
sealed interface InChatUiPart {
    val partId: String

    data class ClockAlarmsPanel(
        override val partId: String,
        val actionSummary: String,
        val highlightAlarmId: String? = null,
        val success: Boolean = true,
        val alarmManagerMode: String? = null
    ) : InChatUiPart

    data class ClockTimerPanel(
        override val partId: String,
        val actionSummary: String,
        val highlightTimerId: String? = null,
        val success: Boolean = true
    ) : InChatUiPart

    data class ClockStopwatchPanel(
        override val partId: String,
        val actionSummary: String,
        val success: Boolean = true
    ) : InChatUiPart

    data class CalendarEventsPanel(
        override val partId: String,
        val actionSummary: String,
        val highlightEventId: String? = null,
        val success: Boolean = true,
        val calendarSyncMode: String? = null
    ) : InChatUiPart

    data class GenUiCardPart(
        override val partId: String,
        val spec: GenUiCardSpec
    ) : InChatUiPart

    data class ToolStatusBanner(
        override val partId: String,
        val toolName: String,
        val operation: String,
        val success: Boolean,
        val message: String
    ) : InChatUiPart

    data class DeviceActionPanel(
        override val partId: String,
        val category: String,
        val title: String,
        val summary: String,
        val badge: String,
        val success: Boolean = true,
        val detailLines: List<String> = emptyList()
    ) : InChatUiPart

    data class ScreenCapturePanel(
        override val partId: String,
        val title: String,
        val summary: String,
        val badge: String,
        val success: Boolean = true,
        val captureId: String? = null,
        val savedUriString: String? = null,
        val detailLines: List<String> = emptyList(),
        val autoAnalyzePrompt: String? = null
    ) : InChatUiPart

    data class ToolChainStatusPanel(
        override val partId: String,
        val snapshot: ToolChainExecutionSnapshot
    ) : InChatUiPart

    data class OcrResultPanel(
        override val partId: String,
        val title: String,
        val extractedText: String,
        val sourceLabel: String,
        val captureId: String? = null,
        val success: Boolean = true
    ) : InChatUiPart

    data class NotesPanel(
        override val partId: String,
        val actionSummary: String,
        val highlightNoteId: String? = null,
        val success: Boolean = true
    ) : InChatUiPart

    data class VoiceCallRelayPanel(
        override val partId: String,
        val title: String,
        val summary: String,
        val targetLabel: String,
        val success: Boolean = true
    ) : InChatUiPart

    data class ClipboardPanel(
        override val partId: String,
        val title: String,
        val summary: String,
        val clipboardText: String?,
        val badge: String,
        val success: Boolean = true
    ) : InChatUiPart

    data class PhoneDialerPanel(
        override val partId: String,
        val title: String,
        val summary: String,
        val phoneNumber: String,
        val contactLabel: String? = null,
        val badge: String,
        val accessibilityReady: Boolean = false,
        val success: Boolean = true,
        val detailLines: List<String> = emptyList()
    ) : InChatUiPart

    data class NetworkStatusPanel(
        override val partId: String,
        val title: String,
        val summary: String,
        val badge: String,
        val transportLabel: String,
        val isConnected: Boolean,
        val wifiSsid: String? = null,
        val signalBars: Int = 0,
        val linkSpeedMbps: Int? = null,
        val localIpv4: String? = null,
        val latencyMs: Long? = null,
        val success: Boolean = true
    ) : InChatUiPart

    data class CameraControlPanel(
        override val partId: String,
        val title: String,
        val summary: String,
        val badge: String,
        val torchEnabled: Boolean = false,
        val mode: String = "photo",
        val facing: String = "back",
        val success: Boolean = true,
        val detailLines: List<String> = emptyList()
    ) : InChatUiPart

    data class AppControlPanel(
        override val partId: String,
        val title: String,
        val summary: String,
        val badge: String,
        val targetLabel: String? = null,
        val cursorX: Float? = null,
        val cursorY: Float? = null,
        val interactiveElements: List<String> = emptyList(),
        val success: Boolean = true
    ) : InChatUiPart
}

@Serializable
data class ChatToolInvocation(
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap())
)

data class ParsedStreamSnapshot(
    val cleanDisplayText: String,
    val pendingToolHint: String?,
    val completedToolJsonBlocks: List<String>
)

/**
 * Real-time streaming tool-call & Generative UI protocol for Chat Hub.
 * Allows the LLM to stream natural conversation AND invoke real AlarmManager,
 * live Countdown Timer, Stopwatch, Calendar, Camera, Screenshot, App Launcher,
 * Device Navigation, and Generative UI tools mid-stream without leaking raw JSON.
 */
object ChatGenUiToolProtocol {

    const val OPEN_TAG = "<<<TOOL_CALL>>>"
    const val CLOSE_TAG = "<<<END_TOOL_CALL>>>"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun buildToolSystemInstructions(
        clockContext: String,
        enabledToolIds: Set<String> = GenUiToolRegistry.ALL_TOOL_IDS
    ): String = buildString {
        if (enabledToolIds.isEmpty()) {
            appendLine("NOTE: All interactive device tools are currently disabled by the user in Tools & Permissions. Answer normally in plain text and let the user know they can re-enable tools in the Tools & Privacy tab if they request device automation, alarms, timers, calendar, camera, screenshots, or navigation.")
            return@buildString
        }

        appendLine("REAL-TIME AUTOMATIC DEVICE TOOLS & SEQUENTIAL CHAINING PROTOCOL (CRITICAL ENGINE VERIFICATION RULES):")
        appendLine("1. MANDATORY EXECUTION PROOF: You MUST emit a $OPEN_TAG...$CLOSE_TAG block on EVERY turn where the user requests ANY action (alarms, timers, stopwatch, calendar, camera, flashlight, screenshot, OCR, notes, clipboard read/write/paste, sharing, opening apps, Chrome/YouTube search, dialing phone, live voice call relay, navigation). NEVER say 'Done', 'Set', 'Created', 'Saved', 'Copied', or 'Opened' in plain text without emitting the matching $OPEN_TAG block in THIS EXACT response!")
        appendLine("2. MULTI-STEP SEQUENTIAL TOOL CHAINS: Emit multiple $OPEN_TAG...$CLOSE_TAG blocks in exact execution order OR use runToolChain. Downstream steps automatically inherit intermediate state outputs (\$lastCaptureId, \$lastSavedUri, \$lastExtractedText, \$lastClipboardText, \$lastOpenedApp, \$lastDialedNumber).")
        appendLine("   Example 1 (Screenshot -> OCR -> Notes): $OPEN_TAG{\"tool\":\"takeScreenshot\",\"args\":{\"label\":\"OCR Capture\"}}$CLOSE_TAG $OPEN_TAG{\"tool\":\"ocrAnalysis\",\"args\":{\"captureId\":\"\$lastCaptureId\"}}$CLOSE_TAG $OPEN_TAG{\"tool\":\"saveNote\",\"args\":{\"title\":\"Screen OCR Note\",\"content\":\"\$lastExtractedText\"}}$CLOSE_TAG")
        appendLine("   Example 2 (Chrome + Search + Screenshot): $OPEN_TAG{\"tool\":\"searchInApp\",\"args\":{\"appName\":\"Chrome\",\"query\":\"Android AI news\"}}$CLOSE_TAG $OPEN_TAG{\"tool\":\"takeScreenshot\",\"args\":{\"label\":\"Chrome Search\"}}$CLOSE_TAG")
        appendLine("   Example 3 (Phone + Real-Time STT/TTS Voice Relay): $OPEN_TAG{\"tool\":\"dialPhone\",\"args\":{\"phoneNumber\":\"5550199\"}}$CLOSE_TAG $OPEN_TAG{\"tool\":\"startVoiceCallRelay\",\"args\":{\"targetLabel\":\"Call 5550199\",\"openingGreeting\":\"Hello, this is Mote assistant calling on behalf of my user.\"}}$CLOSE_TAG")
        appendLine("   Example 4 (Clipboard -> Save Note / Search): $OPEN_TAG{\"tool\":\"readClipboard\",\"args\":{}}$CLOSE_TAG $OPEN_TAG{\"tool\":\"saveNote\",\"args\":{\"title\":\"Clipboard Note\",\"content\":\"\$lastClipboardText\"}}$CLOSE_TAG")
        appendLine("3. Never wrap $OPEN_TAG in markdown code fences. Always keep your visible confirmation concise (1-2 sentences).")
        appendLine()

        if (GenUiToolRegistry.TOOL_ALARM_MANAGER in enabledToolIds) {
            appendLine("ALARM MANAGER TOOL (executes immediately in Android AlarmManager):")
            appendLine("- addAlarm: $OPEN_TAG{\"tool\":\"addAlarm\",\"args\":{\"hour\":7,\"minute\":30,\"label\":\"Morning Standup\",\"days\":[\"Mon\",\"Tue\"],\"enabled\":true}}$CLOSE_TAG")
            appendLine("- updateAlarm: $OPEN_TAG{\"tool\":\"updateAlarm\",\"args\":{\"query\":\"Morning Standup\",\"hour\":8,\"minute\":0,\"newLabel\":\"Gym Alarm\",\"enabled\":true}}$CLOSE_TAG")
            appendLine("- deleteAlarm: $OPEN_TAG{\"tool\":\"deleteAlarm\",\"args\":{\"query\":\"Morning Standup\"}}$CLOSE_TAG (use query=\"all\" to clear all)")
            appendLine("- toggleAlarm (set/unset): $OPEN_TAG{\"tool\":\"toggleAlarm\",\"args\":{\"query\":\"Morning Standup\",\"enabled\":false}}$CLOSE_TAG")
            appendLine("- listAlarms: $OPEN_TAG{\"tool\":\"listAlarms\",\"args\":{}}$CLOSE_TAG")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_COUNTDOWN_TIMER in enabledToolIds) {
            appendLine("COUNTDOWN TIMER TOOL (real-time live stream updates):")
            appendLine("- startTimer: $OPEN_TAG{\"tool\":\"startTimer\",\"args\":{\"durationSeconds\":300,\"label\":\"Deep Focus\"}}$CLOSE_TAG")
            appendLine("- pauseTimer / resumeTimer / resetTimer / deleteTimer: $OPEN_TAG{\"tool\":\"pauseTimer\",\"args\":{\"query\":\"Deep Focus\"}}$CLOSE_TAG")
            appendLine("- getTimerStatus (report exact time remaining): $OPEN_TAG{\"tool\":\"getTimerStatus\",\"args\":{\"query\":\"Deep Focus\"}}$CLOSE_TAG")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_STOPWATCH in enabledToolIds) {
            appendLine("STOPWATCH TOOL (real-time centisecond stream updates):")
            appendLine("- startStopwatch / pauseStopwatch / lapStopwatch / resetStopwatch / getStopwatchStatus:")
            appendLine("  $OPEN_TAG{\"tool\":\"startStopwatch\",\"args\":{}}$CLOSE_TAG")
            appendLine("  $OPEN_TAG{\"tool\":\"getStopwatchStatus\",\"args\":{}}$CLOSE_TAG")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_CALENDAR in enabledToolIds) {
            appendLine("CALENDAR TOOL (executes immediately in Android CalendarContract + Room):")
            appendLine("- addEvent: $OPEN_TAG{\"tool\":\"addEvent\",\"args\":{\"title\":\"Design Review\",\"date\":\"2026-11-26\",\"startTime\":\"14:00\",\"endTime\":\"15:00\",\"location\":\"Room 4B\",\"notes\":\"Bring mockups\"}}$CLOSE_TAG")
            appendLine("- updateEvent: $OPEN_TAG{\"tool\":\"updateEvent\",\"args\":{\"query\":\"Design Review\",\"date\":\"2026-11-27\",\"startTime\":\"16:00\",\"endTime\":\"17:00\",\"newTitle\":\"Final Design Review\"}}$CLOSE_TAG")
            appendLine("- deleteEvent: $OPEN_TAG{\"tool\":\"deleteEvent\",\"args\":{\"query\":\"Design Review\"}}$CLOSE_TAG (use query=\"all\" to clear all)")
            appendLine("- listEvents: $OPEN_TAG{\"tool\":\"listEvents\",\"args\":{\"date\":\"today\"}}$CLOSE_TAG (omit date to list all)")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_CAMERA in enabledToolIds) {
            appendLine("CAMERA & FLASHLIGHT TOOL (real-time Android Camera2, lenses, timer, shutter chaining & torch):")
            appendLine("- openCamera: $OPEN_TAG{\"tool\":\"openCamera\",\"args\":{\"mode\":\"photo|video|selfie\",\"facing\":\"back|front\",\"timerSeconds\":3}}$CLOSE_TAG")
            appendLine("- takePhoto / captureSelfie / openVideoCamera: $OPEN_TAG{\"tool\":\"takePhoto\",\"args\":{\"facing\":\"back|front\"}}$CLOSE_TAG")
            appendLine("- pressCameraShutter (glides live cursor to camera shutter button for chained capture -> OCR): $OPEN_TAG{\"tool\":\"pressCameraShutter\",\"args\":{}}$CLOSE_TAG")
            appendLine("- inspectCamera (lists rear/front lenses, focal lengths, flash & torch status): $OPEN_TAG{\"tool\":\"inspectCamera\",\"args\":{}}$CLOSE_TAG")
            appendLine("- toggleFlashlight: $OPEN_TAG{\"tool\":\"toggleFlashlight\",\"args\":{\"enabled\":true}}$CLOSE_TAG (omit enabled to toggle)")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_SCREEN_CAPTURE in enabledToolIds) {
            appendLine("MEDIAPROJECTION SCREEN CAPTURE & OCR ANALYSIS TOOL:")
            appendLine("- takeScreenshot: $OPEN_TAG{\"tool\":\"takeScreenshot\",\"args\":{\"label\":\"Screen Capture\",\"analyze\":false}}$CLOSE_TAG")
            appendLine("- ocrAnalysis (extracts all readable text from \$lastCaptureId or active screen via Gemini Vision OCR): $OPEN_TAG{\"tool\":\"ocrAnalysis\",\"args\":{\"captureId\":\"\$lastCaptureId\"}}$CLOSE_TAG")
            appendLine("- captureAndAnalyzeScreen: $OPEN_TAG{\"tool\":\"captureAndAnalyzeScreen\",\"args\":{\"label\":\"Visual Check\",\"analysisPrompt\":\"Describe everything visible on this screen.\"}}$CLOSE_TAG")
            appendLine("- captureScreenContext: $OPEN_TAG{\"tool\":\"captureScreenContext\",\"args\":{}}$CLOSE_TAG")
            appendLine()
        }

        appendLine("NOTES STORAGE TOOL (persists notes & OCR output into Room DB):")
        appendLine("- saveNote: $OPEN_TAG{\"tool\":\"saveNote\",\"args\":{\"title\":\"Screen OCR Note\",\"content\":\"\$lastExtractedText\",\"source\":\"OCR Chain\"}}$CLOSE_TAG")
        appendLine("- listNotes: $OPEN_TAG{\"tool\":\"listNotes\",\"args\":{}}$CLOSE_TAG")
        appendLine("- deleteNote: $OPEN_TAG{\"tool\":\"deleteNote\",\"args\":{\"query\":\"Screen OCR Note\"}}$CLOSE_TAG")
        appendLine()

        appendLine("SYSTEM CLIPBOARD MANAGER TOOL (reads & writes Android ClipboardManager seamlessly across apps):")
        appendLine("- readClipboard (reads current Android clipboard text into \$lastClipboardText): $OPEN_TAG{\"tool\":\"readClipboard\",\"args\":{}}$CLOSE_TAG")
        appendLine("- writeClipboard (copies text or \$lastExtractedText to Android clipboard): $OPEN_TAG{\"tool\":\"writeClipboard\",\"args\":{\"text\":\"Hello from Mote\",\"label\":\"Mote AI\"}}$CLOSE_TAG")
        appendLine("- clearClipboard (clears Android system clipboard): $OPEN_TAG{\"tool\":\"clearClipboard\",\"args\":{}}$CLOSE_TAG")
        appendLine("- pasteToScreen (pastes clipboard or text into focused input field via Accessibility): $OPEN_TAG{\"tool\":\"pasteToScreen\",\"args\":{\"text\":\"\$lastClipboardText\"}}$CLOSE_TAG")
        appendLine()

        appendLine("PHONE & REAL-TIME VOICE CALL RELAY TOOL (ACTION_DIAL + Accessibility Cursor Call-Click + SMS + STT/TTS Relay):")
        appendLine("- dialPhone: $OPEN_TAG{\"tool\":\"dialPhone\",\"args\":{\"phoneNumber\":\"5550199\",\"contactLabel\":\"Support\"}}$CLOSE_TAG")
        appendLine("- clickCallButton (glides live cursor over the dialer Call button & clicks it via Accessibility): $OPEN_TAG{\"tool\":\"clickCallButton\",\"args\":{}}$CLOSE_TAG")
        appendLine("- sendSms: $OPEN_TAG{\"tool\":\"sendSms\",\"args\":{\"phoneNumber\":\"5550199\",\"message\":\"On my way!\"}}$CLOSE_TAG")
        appendLine("- startVoiceCallRelay (listens continuously via Google Speech-to-Text and replies aloud via Android Text-to-Speech): $OPEN_TAG{\"tool\":\"startVoiceCallRelay\",\"args\":{\"targetLabel\":\"Call Relay\",\"instruction\":\"Answer politely in 1 sentence\",\"openingGreeting\":\"Hello, Mote AI assistant here.\"}}$CLOSE_TAG")
        appendLine("- speakText: $OPEN_TAG{\"tool\":\"speakText\",\"args\":{\"text\":\"Hello, I will be there in 5 minutes.\"}}$CLOSE_TAG")
        appendLine("- stopVoiceCallRelay: $OPEN_TAG{\"tool\":\"stopVoiceCallRelay\",\"args\":{}}$CLOSE_TAG")
        appendLine()

        appendLine("WI-FI & NETWORK TOOLS (real Android ConnectivityManager, WifiManager & Settings.Panel):")
        appendLine("- getNetworkStatus (live Wi-Fi/Cellular/VPN, signal dBm/bars, link speed, IPv4, metered state): $OPEN_TAG{\"tool\":\"getNetworkStatus\",\"args\":{}}$CLOSE_TAG")
        appendLine("- testConnectivity (live TCP ping latency check): $OPEN_TAG{\"tool\":\"testConnectivity\",\"args\":{}}$CLOSE_TAG")
        appendLine("- openWifiPanel / openInternetPanel / openNetworkSettings: $OPEN_TAG{\"tool\":\"openWifiPanel\",\"args\":{}}$CLOSE_TAG")
        appendLine()

        appendLine("AUDIO & VOLUME CONTROL TOOL (real Android AudioManager verified by EventObserver BroadcastReceiver):")
        appendLine("- setVolume: $OPEN_TAG{\"tool\":\"setVolume\",\"args\":{\"percent\":50,\"stream\":\"media|ring|alarm|notification|voice\"}}$CLOSE_TAG")
        appendLine("- adjustVolume: $OPEN_TAG{\"tool\":\"adjustVolume\",\"args\":{\"direction\":\"up|down|mute|unmute\",\"stream\":\"media\"}}$CLOSE_TAG")
        appendLine("- setRingerMode: $OPEN_TAG{\"tool\":\"setRingerMode\",\"args\":{\"mode\":\"normal|vibrate|silent\"}}$CLOSE_TAG")
        appendLine("- getVolumeStatus: $OPEN_TAG{\"tool\":\"getVolumeStatus\",\"args\":{}}$CLOSE_TAG")
        appendLine()

        appendLine("APPLICATION CONTROL & REAL-TIME CURSOR HOVER TOOL (Accessibility live animated cursor over any app):")
        appendLine("- hoverAndClick (visibly glides live floating cursor over UI element and clicks it): $OPEN_TAG{\"tool\":\"hoverAndClick\",\"args\":{\"target\":\"Search\",\"hoverOnly\":false}}$CLOSE_TAG")
        appendLine("- hoverTapCoordinates (glides cursor to screen x/y or 0..1 normalized coords): $OPEN_TAG{\"tool\":\"hoverTapCoordinates\",\"args\":{\"x\":0.5,\"y\":0.5,\"label\":\"Center\",\"longPress\":false}}$CLOSE_TAG")
        appendLine("- swipeScreen (scrolls/swipes up, down, left, right with live cursor): $OPEN_TAG{\"tool\":\"swipeScreen\",\"args\":{\"direction\":\"down\"}}$CLOSE_TAG")
        appendLine("- typeInField (hovers onto input box and enters text): $OPEN_TAG{\"tool\":\"typeInField\",\"args\":{\"target\":\"Search\",\"text\":\"Android AI\"}}$CLOSE_TAG")
        appendLine("- inspectScreenElements (lists clickable controls currently visible on screen): $OPEN_TAG{\"tool\":\"inspectScreenElements\",\"args\":{}}$CLOSE_TAG")
        appendLine()

        if (GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds) {
            appendLine("APP NAVIGATION & SHARING TOOL (UsageStatsManager + PackageManager Manifest + Intent launching/sharing):")
            appendLine("- openApp: $OPEN_TAG{\"tool\":\"openApp\",\"args\":{\"appName\":\"YouTube\"}}$CLOSE_TAG (opens Chrome, YouTube, Gmail, Maps, WhatsApp, Spotify, Phone, or any app)")
            appendLine("- searchInApp (opens Chrome, YouTube, Maps, Play Store, Spotify, or Amazon directly to a search query with the animated Siri overlay): $OPEN_TAG{\"tool\":\"searchInApp\",\"args\":{\"appName\":\"Chrome\",\"query\":\"latest Android AI\"}}$CLOSE_TAG")
            appendLine("- shareToApp: $OPEN_TAG{\"tool\":\"shareToApp\",\"args\":{\"appName\":\"WhatsApp\",\"mediaUri\":\"\$lastSavedUri\",\"text\":\"Here is the screenshot\"}}$CLOSE_TAG")
            appendLine("- openRecentApp: $OPEN_TAG{\"tool\":\"openRecentApp\",\"args\":{\"index\":0}}$CLOSE_TAG")
            appendLine("- getRecentApps: $OPEN_TAG{\"tool\":\"getRecentApps\",\"args\":{\"limit\":8}}$CLOSE_TAG")
            appendLine("- listInstalledApps: $OPEN_TAG{\"tool\":\"listInstalledApps\",\"args\":{\"query\":\"\"}}$CLOSE_TAG")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_NAVIGATION in enabledToolIds) {
            appendLine("DEVICE & MAPS NAVIGATION TOOL (system actions, Maps turn-by-turn, URLs, Android Settings, Mote tabs):")
            appendLine("- navigateSystem: $OPEN_TAG{\"tool\":\"navigateSystem\",\"args\":{\"action\":\"back|home|recents|notifications|quick_settings|lock_screen|split_screen\"}}$CLOSE_TAG")
            appendLine("- navigateMaps: $OPEN_TAG{\"tool\":\"navigateMaps\",\"args\":{\"destination\":\"Golden Gate Bridge\",\"mode\":\"driving|walking|bike|transit\"}}$CLOSE_TAG")
            appendLine("- openUrl: $OPEN_TAG{\"tool\":\"openUrl\",\"args\":{\"url\":\"https://developer.android.com\"}}$CLOSE_TAG")
            appendLine("- searchWeb: $OPEN_TAG{\"tool\":\"searchWeb\",\"args\":{\"query\":\"Android LiteRT-LM\"}}$CLOSE_TAG")
            appendLine("- openSettings: $OPEN_TAG{\"tool\":\"openSettings\",\"args\":{\"section\":\"wifi|bluetooth|display|sound|battery|location|accessibility|apps|main\"}}$CLOSE_TAG")
            appendLine("- navigateAppTab: $OPEN_TAG{\"tool\":\"navigateAppTab\",\"args\":{\"tab\":\"chat|genui|models|tools|settings\"}}$CLOSE_TAG")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_GEN_UI_WIDGETS in enabledToolIds) {
            appendLine("GENERATIVE UI WIDGET TOOL (charts, comparisons, checklists, schedules/calendars, timelines, quizzes, budgets, KPIs, device controls):")
            appendLine("- render_gen_ui: $OPEN_TAG{\"tool\":\"render_gen_ui\",\"args\":{\"title\":\"...\",\"subtitle\":\"...\",\"themeAccent\":\"cobalt|emerald|amber|violet|slate\",\"widgets\":[{\"type\":\"bar_chart|metric_grid|calendar|checklist|comparison_table|timeline|quiz_card|slider|toggle|action_chip|reply_pills|info_card\",\"id\":\"w1\",\"label\":\"...\", ...}]}}$CLOSE_TAG")
            appendLine("Widget field hints: bar_chart uses chartBars:[{label,value,unit}]; metric_grid uses metrics:[{label,value,delta}]; checklist uses options:[...]; quiz_card uses options:[...], correctOptionIndex, explanation; timeline uses timelineSteps:[{time,title,detail}]; calendar uses calendarEvents:[{dayLabel,title,timeRange,location}]; comparison_table uses comparisonHeaders:[A,B], comparisonRows:[{feature,leftValue,rightValue}].")
            appendLine()
        }

        appendLine("LIVE DEVICE STATE (use this to report accurate remaining/elapsed time and device status):")
        appendLine(clockContext)
    }

    /**
     * Incrementally parses accumulated streaming text so raw JSON tool calls are hidden cleanly
     * while completed tool blocks are returned immediately for real-time mid-stream execution!
     */
    fun parseStreamingText(rawAccumulated: String): ParsedStreamSnapshot {
        val visible = StringBuilder()
        val completedBlocks = mutableListOf<String>()
        var pendingHint: String? = null

        var cursor = 0
        while (cursor < rawAccumulated.length) {
            val openIdx = rawAccumulated.indexOf(OPEN_TAG, cursor)
            if (openIdx == -1) {
                val partialIdx = findPartialOpenTagStart(rawAccumulated, cursor)
                if (partialIdx != -1) {
                    visible.append(rawAccumulated, cursor, partialIdx)
                    pendingHint = "Preparing interactive tool..."
                } else {
                    visible.append(rawAccumulated, cursor, rawAccumulated.length)
                }
                break
            }

            visible.append(rawAccumulated, cursor, openIdx)
            val contentStart = openIdx + OPEN_TAG.length
            val closeIdx = rawAccumulated.indexOf(CLOSE_TAG, contentStart)
            if (closeIdx == -1) {
                val partialJson = rawAccumulated.substring(contentStart)
                pendingHint = when {
                    partialJson.contains("addAlarm", true) || partialJson.contains("clock_create_alarm", true) ->
                        "Scheduling alarm via AlarmManager..."
                    partialJson.contains("updateAlarm", true) || partialJson.contains("clock_update_alarm", true) ->
                        "Updating alarm in AlarmManager..."
                    partialJson.contains("deleteAlarm", true) || partialJson.contains("clock_delete_alarm", true) ->
                        "Cancelling alarm in AlarmManager..."
                    partialJson.contains("toggleAlarm", true) || partialJson.contains("clock_toggle_alarm", true) ->
                        "Updating alarm switch..."
                    partialJson.contains("listAlarms", true) || partialJson.contains("clock_list_alarms", true) ->
                        "Reading AlarmManager schedule..."
                    partialJson.contains("startTimer", true) || partialJson.contains("clock_start_timer", true) ->
                        "Starting live countdown timer..."
                    partialJson.contains("getTimerStatus", true) ->
                        "Reading live timer remaining..."
                    partialJson.contains("Timer", true) || partialJson.contains("clock_control_timer", true) ->
                        "Updating countdown timer..."
                    partialJson.contains("getStopwatchStatus", true) ->
                        "Reading live stopwatch elapsed..."
                    partialJson.contains("Stopwatch", true) || partialJson.contains("clock_stopwatch", true) ->
                        "Controlling live stopwatch..."
                    partialJson.contains("addEvent", true) || partialJson.contains("calendar_create", true) ->
                        "Creating calendar event..."
                    partialJson.contains("updateEvent", true) || partialJson.contains("calendar_update", true) ->
                        "Updating calendar event..."
                    partialJson.contains("deleteEvent", true) || partialJson.contains("calendar_delete", true) ->
                        "Removing calendar event..."
                    partialJson.contains("listEvents", true) || partialJson.contains("calendar_list", true) ->
                        "Loading calendar schedule..."
                    partialJson.contains("Flashlight", true) || partialJson.contains("torch", true) ->
                        "Toggling camera flashlight..."
                    partialJson.contains("Camera", true) || partialJson.contains("takePhoto", true) ->
                        "Launching Android Camera..."
                    partialJson.contains("captureAndAnalyze", true) ->
                        "Requesting MediaProjection capture for visual analysis..."
                    partialJson.contains("Screenshot", true) || partialJson.contains("ScreenContext", true) ->
                        "Requesting MediaProjection screen capture..."
                    partialJson.contains("RecentApp", true) || partialJson.contains("getRecentApps", true) ->
                        "Reading app UsageStats history..."
                    partialJson.contains("shareToApp", true) || partialJson.contains("shareContent", true) ->
                        "Sharing content to target app..."
                    partialJson.contains("runToolChain", true) ->
                        "Orchestrating sequential tool chain..."
                    partialJson.contains("openApp", true) || partialJson.contains("listInstalledApps", true) ->
                        "Launching Android app..."
                    partialJson.contains("navigateMaps", true) ->
                        "Starting Maps navigation..."
                    partialJson.contains("navigate", true) || partialJson.contains("openUrl", true) || partialJson.contains("searchWeb", true) || partialJson.contains("openSettings", true) ->
                        "Executing device navigation..."
                    partialJson.contains("readClipboard", true) || partialJson.contains("getClipboard", true) ->
                        "Reading Android system clipboard..."
                    partialJson.contains("writeClipboard", true) || partialJson.contains("copyToClipboard", true) ->
                        "Copying text to system clipboard..."
                    partialJson.contains("clearClipboard", true) ->
                        "Clearing system clipboard..."
                    partialJson.contains("pasteToScreen", true) ->
                        "Pasting clipboard into focused field..."
                    partialJson.contains("Wifi", true) || partialJson.contains("Network", true) || partialJson.contains("Connectivity", true) ->
                        "Checking live Wi-Fi & Network state..."
                    partialJson.contains("hover", true) || partialJson.contains("swipe", true) || partialJson.contains("inspectScreen", true) || partialJson.contains("clickCall", true) ->
                        "Gliding real-time Mote cursor..."
                    partialJson.contains("Volume", true) || partialJson.contains("Ringer", true) || partialJson.contains("muteAudio", true) ->
                        "Updating Android AudioManager & verifying via EventObserver..."
                    partialJson.contains("render_gen_ui", true) || partialJson.contains("\"widgets\"", true) ->
                        "Streaming Generative UI widget..."
                    else -> "Executing live tool..."
                }
                break
            } else {
                val blockJson = rawAccumulated.substring(contentStart, closeIdx).trim()
                if (blockJson.isNotEmpty()) {
                    completedBlocks.add(blockJson)
                }
                cursor = closeIdx + CLOSE_TAG.length
            }
        }

        val cleaned = visible.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return ParsedStreamSnapshot(
            cleanDisplayText = cleaned,
            pendingToolHint = pendingHint,
            completedToolJsonBlocks = completedBlocks
        )
    }

    fun decodeToolInvocation(rawJson: String): ChatToolInvocation? {
        return try {
            val cleaned = rawJson
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val obj = json.parseToJsonElement(cleaned).jsonObject
            val explicitTool = (obj["tool"] ?: obj["name"] ?: obj["function"])
                ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            if (explicitTool != null) {
                val args = (obj["args"] ?: obj["arguments"] ?: obj["parameters"])
                    ?.let { el -> runCatching { el.jsonObject }.getOrNull() }
                    ?: JsonObject(emptyMap())
                return ChatToolInvocation(tool = explicitTool, args = args)
            }
            // Auto-wrap raw GenUI card JSON ({"title": "...", "widgets": [...]}) into render_gen_ui
            if (obj.containsKey("widgets") && obj.containsKey("title")) {
                return ChatToolInvocation(tool = "render_gen_ui", args = obj)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Normalizes any valid tool call JSON or raw GenUI card spec into canonical
     * `{"tool": "...", "args": {...}}` JSON string so downstream parsers never fail.
     */
    fun normalizeToolOrGenUiJson(rawJson: String): String? {
        val decoded = decodeToolInvocation(rawJson) ?: return null
        return buildJsonObject {
            put("tool", decoded.tool)
            put("args", decoded.args)
        }.toString()
    }

    /**
     * Post-stream safety sweep: extracts and strips any remaining `<<<TOOL_CALL>>>...`,
     * fenced JSON blocks, or bare balanced JSON objects that represent tool calls or
     * GenUI cards so raw JSON NEVER leaks into the visible chat bubble.
     */
    fun extractAndStripRawJsonArtifacts(rawText: String): Pair<String, List<String>> {
        if (rawText.isBlank()) return "" to emptyList()
        val extractedBlocks = mutableListOf<String>()
        var working = rawText

        // 1. Strip any residual tagged <<<TOOL_CALL>>> ... <<<END_TOOL_CALL>>> blocks
        val taggedRegex = Regex(
            "${Regex.escape(OPEN_TAG)}([\\s\\S]*?)(?:${Regex.escape(CLOSE_TAG)}|$)"
        )
        working = taggedRegex.replace(working) { m ->
            val inner = m.groupValues[1].trim()
            normalizeToolOrGenUiJson(inner)?.let { extractedBlocks += it }
            ""
        }

        // 2. Strip fenced ```json ... ``` or ``` ... ``` blocks if they contain tool/GenUI JSON
        val fencedRegex = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
        working = fencedRegex.replace(working) { m ->
            val inner = m.groupValues[1].trim()
            val normalized = normalizeToolOrGenUiJson(inner)
            if (normalized != null) {
                extractedBlocks += normalized
                ""
            } else {
                m.value
            }
        }

        // 3. Scan for bare balanced JSON objects representing tool invocations or GenUI cards
        val cleaned = StringBuilder()
        var idx = 0
        while (idx < working.length) {
            if (working[idx] == '{') {
                val endIdx = findBalancedJsonEnd(working, idx)
                if (endIdx != -1) {
                    val candidate = working.substring(idx, endIdx + 1)
                    val normalized = normalizeToolOrGenUiJson(candidate)
                    if (normalized != null) {
                        extractedBlocks += normalized
                        idx = endIdx + 1
                        continue
                    }
                }
            }
            cleaned.append(working[idx])
            idx++
        }

        val finalText = cleaned.toString()
            .replace(OPEN_TAG, "")
            .replace(CLOSE_TAG, "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return finalText to extractedBlocks.distinct()
    }

    /**
     * Defensive UI-layer helper: strips raw tool/GenUI JSON from any stored or live message text
     * and extracts an inline [GenUiCardSpec] if a raw GenUI card JSON was embedded in the text.
     */
    fun tryExtractInlineGenUiCard(text: String): Pair<String, GenUiCardSpec?> {
        if (!text.contains("{") && !text.contains(OPEN_TAG)) return text to null
        val (cleanText, blocks) = extractAndStripRawJsonArtifacts(text)
        var cardSpec: GenUiCardSpec? = null
        for (block in blocks) {
            val inv = decodeToolInvocation(block) ?: continue
            if (inv.tool.equals("render_gen_ui", ignoreCase = true) ||
                inv.tool.equals("renderGenUi", ignoreCase = true)
            ) {
                val decoded = decodeGenUiSpec(inv.args)
                if (decoded != null) {
                    cardSpec = decoded
                    break
                }
            }
        }
        return cleanText to cardSpec
    }

    private fun findBalancedJsonEnd(text: String, startIndex: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIndex until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    fun decodeGenUiSpec(args: JsonObject): GenUiCardSpec? {
        return try {
            json.decodeFromString(GenUiCardSpec.serializer(), args.toString())
        } catch (_: Exception) {
            null
        }
    }

    fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.intVal(key: String): Int? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.intOrNull
            ?: prim.doubleOrNull?.toInt()
            ?: prim.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
    }

    fun JsonObject.floatVal(key: String): Float? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.floatOrNull
            ?: prim.doubleOrNull?.toFloat()
            ?: prim.contentOrNull?.trim()?.toFloatOrNull()
    }

    fun JsonObject.boolVal(key: String): Boolean? {
        val prim = this[key] as? JsonPrimitive ?: return null
        prim.booleanOrNull?.let { return it }
        return when (prim.contentOrNull?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    fun JsonObject.strList(key: String): List<String> {
        val el = this[key] ?: return emptyList()
        return when (el) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            is JsonPrimitive -> el.contentOrNull
                ?.split(',', ';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            else -> emptyList()
        }
    }

    private fun findPartialOpenTagStart(text: String, fromIndex: Int): Int {
        val maxCheck = minOf(OPEN_TAG.length - 1, text.length - fromIndex)
        for (len in maxCheck downTo 1) {
            val start = text.length - len
            if (text.regionMatches(start, OPEN_TAG, 0, len)) {
                return start
            }
        }
        return -1
    }
}
