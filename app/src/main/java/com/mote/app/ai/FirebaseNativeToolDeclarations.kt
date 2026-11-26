package com.mote.app.ai

import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.encodeToString
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
 */
object FirebaseNativeToolDeclarations {

    private val serializerJson = Json { encodeDefaults = false }

    fun buildTools(enabledToolIds: Set<String>): List<Tool> {
        val declarations = mutableListOf<FunctionDeclaration>()

        if (GenUiToolRegistry.TOOL_ALARM_MANAGER in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "addAlarm",
                description = "Schedule a new Android alarm via AlarmManager at a specific hour/minute or relative offset in minutes.",
                parameters = mapOf(
                    "hour" to Schema.integer("24-hour clock hour (0-23)"),
                    "minute" to Schema.integer("Minute of hour (0-59)"),
                    "inMinutes" to Schema.integer("Relative offset in minutes from now"),
                    "label" to Schema.string("Short alarm label"),
                    "enabled" to Schema.boolean("Whether alarm is enabled immediately")
                ),
                optionalParameters = listOf("hour", "minute", "inMinutes", "label", "enabled")
            )
            declarations += FunctionDeclaration(
                name = "updateAlarm",
                description = "Update an existing alarm's time, label, or enabled state.",
                parameters = mapOf(
                    "query" to Schema.string("Existing alarm label, time, or ID"),
                    "hour" to Schema.integer("New 24-hour hour (0-23)"),
                    "minute" to Schema.integer("New minute (0-59)"),
                    "newLabel" to Schema.string("Updated alarm label"),
                    "enabled" to Schema.boolean("Updated enabled status")
                ),
                optionalParameters = listOf("query", "hour", "minute", "newLabel", "enabled")
            )
            declarations += FunctionDeclaration(
                name = "toggleAlarm",
                description = "Enable or disable a matching alarm or 'all' alarms.",
                parameters = mapOf(
                    "query" to Schema.string("Alarm label, time, ID, or 'all'"),
                    "enabled" to Schema.boolean("True to turn on, false to turn off")
                ),
                optionalParameters = listOf("query")
            )
            declarations += FunctionDeclaration(
                name = "snoozeAlarm",
                description = "Snooze/push forward an existing alarm by a number of minutes (default 10).",
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
                parameters = emptyMap()
            )
        }

        if (GenUiToolRegistry.TOOL_COUNTDOWN_TIMER in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "startTimer",
                description = "Start a live ticking countdown timer in seconds.",
                parameters = mapOf(
                    "durationSeconds" to Schema.integer("Total duration in seconds (1-86400)"),
                    "label" to Schema.string("Optional timer label")
                ),
                optionalParameters = listOf("label")
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
                description = "Control the live centisecond stopwatch: start, pause, resume, lap, reset, clearLaps, or status.",
                parameters = mapOf(
                    "action" to Schema.enumeration(
                        listOf("start", "pause", "resume", "lap", "reset", "clearLaps", "status"),
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
                    "date" to Schema.string("ISO date YYYY-MM-DD"),
                    "startTime" to Schema.string("Start time e.g. 14:00"),
                    "endTime" to Schema.string("End time e.g. 15:00"),
                    "location" to Schema.string("Optional location")
                ),
                optionalParameters = listOf("date", "startTime", "endTime", "location")
            )
            declarations += FunctionDeclaration(
                name = "listEvents",
                description = "List saved calendar events.",
                parameters = mapOf(
                    "date" to Schema.string("Optional ISO date filter YYYY-MM-DD")
                ),
                optionalParameters = listOf("date")
            )
        }

        if (GenUiToolRegistry.TOOL_CAMERA in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "openCamera",
                description = "Open the Android Camera in photo, selfie, or video mode.",
                parameters = mapOf(
                    "mode" to Schema.enumeration(listOf("photo", "video"), "Capture mode"),
                    "facing" to Schema.enumeration(listOf("back", "front"), "Camera lens facing"),
                    "timerSeconds" to Schema.integer("Optional countdown timer seconds")
                ),
                optionalParameters = listOf("mode", "facing", "timerSeconds")
            )
            declarations += FunctionDeclaration(
                name = "toggleFlashlight",
                description = "Turn the Android Camera2 flashlight torch on or off.",
                parameters = mapOf(
                    "enabled" to Schema.boolean("True for ON, false for OFF")
                ),
                optionalParameters = listOf("enabled")
            )
        }

        if (GenUiToolRegistry.TOOL_SCREEN_CAPTURE in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "takeScreenshot",
                description = "Capture the current Android screen via MediaProjection/Accessibility.",
                parameters = mapOf(
                    "label" to Schema.string("Optional capture label")
                ),
                optionalParameters = listOf("label")
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
                parameters = emptyMap()
            )
        }

        if (GenUiToolRegistry.TOOL_VOICE_CALL in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "dialPhone",
                description = "Open the Android Phone dialer pre-filled with a phone number.",
                parameters = mapOf(
                    "phoneNumber" to Schema.string("Phone number to dial")
                )
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
        }

        if (GenUiToolRegistry.TOOL_NAVIGATION in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "navigateMaps",
                description = "Launch Google Maps turn-by-turn navigation to a destination.",
                parameters = mapOf(
                    "destination" to Schema.string("Address or place name")
                )
            )
        }

        if (GenUiToolRegistry.TOOL_CLIPBOARD in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "readClipboard",
                description = "Read current text from the Android system clipboard.",
                parameters = emptyMap()
            )
            declarations += FunctionDeclaration(
                name = "writeClipboard",
                description = "Write text into the Android system clipboard.",
                parameters = mapOf(
                    "text" to Schema.string("Text to copy into clipboard")
                )
            )
        }

        if (GenUiToolRegistry.TOOL_NETWORK in enabledToolIds) {
            declarations += FunctionDeclaration(
                name = "getNetworkStatus",
                description = "Inspect live Wi-Fi, cellular, IP, and connectivity status.",
                parameters = emptyMap()
            )
            declarations += FunctionDeclaration(
                name = "openWifiPanel",
                description = "Open Android's native Wi-Fi / Internet Settings Panel.",
                parameters = emptyMap()
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
                name = "swipeScreen",
                description = "Perform a real directional scroll/swipe gesture with visual cursor tracking.",
                parameters = mapOf(
                    "direction" to Schema.enumeration(listOf("up", "down", "left", "right"), "Swipe direction")
                )
            )
            declarations += FunctionDeclaration(
                name = "inspectScreenElements",
                description = "List visible clickable and editable controls on the foreground app screen.",
                parameters = emptyMap()
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
