package com.mote.app.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        appendLine("1. MANDATORY EXECUTION PROOF: You MUST emit a $OPEN_TAG...$CLOSE_TAG block on EVERY turn where the user requests ANY action (alarms, timers, stopwatch, calendar, camera, flashlight, screenshot, sharing, opening apps, navigation). NEVER say 'Done', 'Set', 'Created', or 'Opened' in plain text without emitting the matching $OPEN_TAG block in THIS EXACT response — prior chat messages omit tool tags, so always emit fresh tags!")
        appendLine("2. MULTI-STEP SEQUENTIAL TOOL CHAINS: When the user asks for multiple dependent steps (e.g. 'Take a screenshot and share it on WhatsApp' or 'Set a 7am alarm and start a 5m timer'), emit multiple $OPEN_TAG...$CLOSE_TAG blocks in exact execution order OR use runToolChain. Downstream steps automatically inherit intermediate outputs like \$lastSavedUri and \$lastCaptureId.")
        appendLine("   Example chain: $OPEN_TAG{\"tool\":\"takeScreenshot\",\"args\":{\"label\":\"Share Snapshot\"}}$CLOSE_TAG $OPEN_TAG{\"tool\":\"shareToApp\",\"args\":{\"appName\":\"WhatsApp\",\"mediaUri\":\"\$lastSavedUri\",\"text\":\"Check out this screenshot\"}}$CLOSE_TAG")
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
            appendLine("CAMERA & FLASHLIGHT TOOL (real-time Android Camera & Camera2 Torch):")
            appendLine("- openCamera: $OPEN_TAG{\"tool\":\"openCamera\",\"args\":{\"mode\":\"photo|video\",\"facing\":\"back|front\"}}$CLOSE_TAG")
            appendLine("- takePhoto: $OPEN_TAG{\"tool\":\"takePhoto\",\"args\":{\"facing\":\"back|front\"}}$CLOSE_TAG")
            appendLine("- openVideoCamera: $OPEN_TAG{\"tool\":\"openVideoCamera\",\"args\":{}}$CLOSE_TAG")
            appendLine("- toggleFlashlight: $OPEN_TAG{\"tool\":\"toggleFlashlight\",\"args\":{\"enabled\":true}}$CLOSE_TAG (omit enabled to toggle)")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_SCREEN_CAPTURE in enabledToolIds) {
            appendLine("MEDIAPROJECTION SCREEN CAPTURE TOOL (explicit user-authorized screenshot surfaced as a Bitmap in chat for visual analysis):")
            appendLine("- takeScreenshot: $OPEN_TAG{\"tool\":\"takeScreenshot\",\"args\":{\"label\":\"Screen Capture\",\"analyze\":false}}$CLOSE_TAG")
            appendLine("- captureAndAnalyzeScreen (takes MediaProjection screenshot AND immediately runs visual AI analysis on the bitmap): $OPEN_TAG{\"tool\":\"captureAndAnalyzeScreen\",\"args\":{\"label\":\"Visual Check\",\"analysisPrompt\":\"Describe everything visible on this screen and highlight key details.\"}}$CLOSE_TAG")
            appendLine("- captureScreenContext: $OPEN_TAG{\"tool\":\"captureScreenContext\",\"args\":{}}$CLOSE_TAG")
            appendLine()
        }

        if (GenUiToolRegistry.TOOL_APP_LAUNCHER in enabledToolIds) {
            appendLine("APP NAVIGATION & SHARING TOOL (UsageStatsManager + PackageManager Manifest + Intent launching/sharing):")
            appendLine("- openApp: $OPEN_TAG{\"tool\":\"openApp\",\"args\":{\"appName\":\"YouTube\"}}$CLOSE_TAG (opens Chrome, YouTube, Gmail, Maps, WhatsApp, Spotify, or any app in the Installed Apps Manifest)")
            appendLine("- shareToApp (shares a screenshot URI or text directly to WhatsApp, Telegram, Gmail, Messages, etc.): $OPEN_TAG{\"tool\":\"shareToApp\",\"args\":{\"appName\":\"WhatsApp\",\"mediaUri\":\"\$lastSavedUri\",\"text\":\"Here is the screenshot\"}}$CLOSE_TAG")
            appendLine("- openRecentApp (re-opens most recently used app via UsageStatsManager): $OPEN_TAG{\"tool\":\"openRecentApp\",\"args\":{\"index\":0}}$CLOSE_TAG")
            appendLine("- getRecentApps (lists recently used apps with UsageStats foreground time): $OPEN_TAG{\"tool\":\"getRecentApps\",\"args\":{\"limit\":8}}$CLOSE_TAG")
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
                    partialJson.contains("render_gen_ui", true) ->
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
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val obj = json.parseToJsonElement(cleaned).jsonObject
            val tool = obj["tool"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                ?: return null
            val args = obj["args"]?.jsonObject ?: JsonObject(emptyMap())
            ChatToolInvocation(tool = tool, args = args)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeGenUiSpec(args: JsonObject): GenUiCardSpec? {
        return try {
            json.decodeFromString(GenUiCardSpec.serializer(), args.toString())
        } catch (_: Exception) {
            null
        }
    }

    fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    fun JsonObject.intVal(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    fun JsonObject.boolVal(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    fun JsonObject.strList(key: String): List<String> =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

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
