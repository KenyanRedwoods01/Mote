package com.mote.app.ai

import com.mote.app.ai.ChatGenUiToolProtocol.boolVal
import com.mote.app.ai.ChatGenUiToolProtocol.intVal
import com.mote.app.ai.ChatGenUiToolProtocol.str
import com.mote.app.ai.ChatGenUiToolProtocol.strList
import com.mote.app.control.CalendarToolsManager
import com.mote.app.control.ClockToolsManager
import com.mote.app.data.db.LocalModelDao
import com.mote.app.data.db.ModelStudioPrefsEntity
import com.mote.app.data.db.ToolExecutionLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.Locale

data class GenUiToolResult(
    val toolId: String,
    val operation: String,
    val success: Boolean,
    val feedback: String,
    val uiPart: InChatUiPart?
)

data class ToolExecutionAuditEntry(
    val id: String,
    val timestampMs: Long,
    val toolId: String,
    val operation: String,
    val success: Boolean,
    val blocked: Boolean = false,
    val feedback: String
)

data class ToolUsageBucket(
    val toolId: String,
    val displayName: String,
    val totalCalls: Int,
    val successCalls: Int,
    val blockedCalls: Int,
    val shareFraction: Float
)

data class ToolUsageStatsSnapshot(
    val totalCalls: Int = 0,
    val successfulCalls: Int = 0,
    val blockedCalls: Int = 0,
    val topToolName: String? = null,
    val buckets: List<ToolUsageBucket> = emptyList()
) {
    val successRatePct: Int
        get() = if (totalCalls <= 0) 0 else ((successfulCalls * 100f) / totalCalls.toFloat()).toInt()
}

data class RegisteredToolDescriptor(
    val id: String,
    val displayName: String,
    val category: String,
    val description: String,
    val operations: List<String>,
    val requiredPermissionLabels: List<String>
)

/**
 * Standard Tool interface for the Generative UI Tool Registry.
 */
interface GenUiTool {
    val descriptor: RegisteredToolDescriptor
    val supportedOperations: Set<String>
    suspend fun execute(operation: String, args: JsonObject): GenUiToolResult
}

/**
 * Dedicated Tool interface for Alarm management backed by Android's AlarmManager.
 */
interface AlarmManagementTool : GenUiTool {
    suspend fun addAlarm(
        hour: Int,
        minute: Int,
        label: String,
        days: List<String> = emptyList(),
        enabled: Boolean = true
    ): GenUiToolResult

    suspend fun updateAlarm(
        query: String?,
        hour: Int?,
        minute: Int?,
        newLabel: String?,
        enabled: Boolean?
    ): GenUiToolResult

    suspend fun deleteAlarm(query: String?): GenUiToolResult

    suspend fun toggleAlarm(query: String?, enabled: Boolean): GenUiToolResult

    suspend fun listAlarms(): GenUiToolResult
}

/**
 * Dedicated Tool interface for Calendar management backed by Room & Android CalendarContract.
 */
interface CalendarManagementTool : GenUiTool {
    suspend fun addEvent(
        title: String,
        dateIso: String?,
        startTime: String?,
        endTime: String?,
        location: String = "",
        notes: String = ""
    ): GenUiToolResult

    suspend fun updateEvent(
        query: String?,
        newTitle: String?,
        newDateIso: String?,
        newStartTime: String?,
        newEndTime: String?,
        newLocation: String?,
        newNotes: String?
    ): GenUiToolResult

    suspend fun deleteEvent(query: String?): GenUiToolResult

    suspend fun listEvents(dateFilter: String? = null): GenUiToolResult
}

/**
 * Android AlarmManager implementation of [AlarmManagementTool].
 */
class AndroidAlarmManagerTool(
    private val clockTools: ClockToolsManager
) : AlarmManagementTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_ALARM_MANAGER,
        displayName = "Alarm Manager",
        category = "Clock · AlarmManager",
        description = "Schedules, updates, toggles, and deletes alarms using Android's AlarmManager.",
        operations = listOf("addAlarm", "updateAlarm", "deleteAlarm", "toggleAlarm", "listAlarms"),
        requiredPermissionLabels = listOf("Exact Alarms", "Notifications")
    )

    override val supportedOperations: Set<String> = setOf(
        "addalarm", "clock_create_alarm",
        "updatealarm", "clock_update_alarm",
        "deletealarm", "clock_delete_alarm",
        "togglealarm", "clock_toggle_alarm",
        "listalarms", "clock_list_alarms"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        return when (operation.lowercase(Locale.US)) {
            "addalarm", "clock_create_alarm" -> addAlarm(
                hour = args.intVal("hour") ?: 7,
                minute = args.intVal("minute") ?: 0,
                label = args.str("label") ?: "Alarm",
                days = args.strList("days"),
                enabled = args.boolVal("enabled") ?: true
            )
            "updatealarm", "clock_update_alarm" -> updateAlarm(
                query = args.str("query") ?: args.str("id") ?: args.str("alarmId") ?: args.str("label"),
                hour = args.intVal("hour") ?: args.intVal("newHour"),
                minute = args.intVal("minute") ?: args.intVal("newMinute"),
                newLabel = args.str("newLabel") ?: args.str("label"),
                enabled = args.boolVal("enabled")
            )
            "deletealarm", "clock_delete_alarm" -> deleteAlarm(
                query = args.str("query") ?: args.str("id") ?: args.str("alarmId") ?: args.str("label")
            )
            "togglealarm", "clock_toggle_alarm" -> toggleAlarm(
                query = args.str("query") ?: args.str("id") ?: args.str("label"),
                enabled = args.boolVal("enabled") ?: false
            )
            "listalarms", "clock_list_alarms" -> listAlarms()
            else -> GenUiToolResult(
                toolId = descriptor.id,
                operation = operation,
                success = false,
                feedback = "Unsupported alarm operation: $operation",
                uiPart = null
            )
        }
    }

    override suspend fun addAlarm(
        hour: Int,
        minute: Int,
        label: String,
        days: List<String>,
        enabled: Boolean
    ): GenUiToolResult {
        val res = clockTools.createAlarm(hour, minute, label, days, enabled)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "addAlarm",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.ClockAlarmsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightAlarmId = res.targetAlarmId,
                success = res.success,
                alarmManagerMode = res.alarmManagerMode
            )
        )
    }

    override suspend fun updateAlarm(
        query: String?,
        hour: Int?,
        minute: Int?,
        newLabel: String?,
        enabled: Boolean?
    ): GenUiToolResult {
        val res = clockTools.updateAlarm(query, hour, minute, newLabel, enabled)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "updateAlarm",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.ClockAlarmsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightAlarmId = res.targetAlarmId,
                success = res.success,
                alarmManagerMode = res.alarmManagerMode
            )
        )
    }

    override suspend fun deleteAlarm(query: String?): GenUiToolResult {
        val res = clockTools.deleteAlarm(query)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "deleteAlarm",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.ClockAlarmsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightAlarmId = null,
                success = res.success,
                alarmManagerMode = res.alarmManagerMode
            )
        )
    }

    override suspend fun toggleAlarm(query: String?, enabled: Boolean): GenUiToolResult {
        val res = clockTools.setAlarmEnabled(query, enabled)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "toggleAlarm",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.ClockAlarmsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightAlarmId = res.targetAlarmId,
                success = res.success,
                alarmManagerMode = res.alarmManagerMode
            )
        )
    }

    override suspend fun listAlarms(): GenUiToolResult {
        val count = clockTools.alarms.value.size
        val summary = if (count == 0) "No alarms scheduled." else "Showing $count AlarmManager schedule(s)"
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "listAlarms",
            success = true,
            feedback = summary,
            uiPart = InChatUiPart.ClockAlarmsPanel(
                partId = nextPartId(),
                actionSummary = summary,
                highlightAlarmId = null,
                success = true,
                alarmManagerMode = if (clockTools.canScheduleExactAlarms()) "Exact AlarmManager" else "Inexact AlarmManager"
            )
        )
    }
}

/**
 * Countdown Timer tool providing real-time stream updates and remaining-time status queries.
 */
class CountdownTimerTool(
    private val clockTools: ClockToolsManager
) : GenUiTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_COUNTDOWN_TIMER,
        displayName = "Countdown Timer",
        category = "Clock · Live Stream",
        description = "Starts, pauses, resumes, resets, deletes, and streams live time-remaining status.",
        operations = listOf("startTimer", "pauseTimer", "resumeTimer", "resetTimer", "deleteTimer", "getTimerStatus"),
        requiredPermissionLabels = listOf("Vibration")
    )

    override val supportedOperations: Set<String> = setOf(
        "starttimer", "clock_start_timer",
        "pausetimer", "resumetimer", "resettimer", "deletetimer",
        "gettimerstatus", "clock_control_timer"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        val normalized = operation.lowercase(Locale.US)
        val query = args.str("query") ?: args.str("label") ?: args.str("id")
        val res = when (normalized) {
            "starttimer", "clock_start_timer" -> {
                val seconds = args.intVal("durationSeconds")
                    ?: ((args.intVal("minutes") ?: 5) * 60 + (args.intVal("seconds") ?: 0))
                val label = args.str("label") ?: "Countdown"
                clockTools.startTimer(seconds, label)
            }
            "pausetimer" -> clockTools.controlTimer("pause", query)
            "resumetimer" -> clockTools.controlTimer("resume", query)
            "resettimer" -> clockTools.controlTimer("reset", query)
            "deletetimer" -> clockTools.controlTimer("delete", query)
            "gettimerstatus" -> clockTools.getCountdownStatusSummary(query)
            "clock_control_timer" -> clockTools.controlTimer(args.str("action") ?: "status", query)
            else -> clockTools.getCountdownStatusSummary(query)
        }
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = operation,
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.ClockTimerPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightTimerId = res.targetTimerId,
                success = res.success
            )
        )
    }
}

/**
 * Stopwatch tool providing real-time centisecond elapsed status and lap splits.
 */
class StopwatchTool(
    private val clockTools: ClockToolsManager
) : GenUiTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_STOPWATCH,
        displayName = "Stopwatch",
        category = "Clock · Live Stream",
        description = "Controls live stopwatch start, pause, lap splits, reset, and elapsed-time status.",
        operations = listOf("startStopwatch", "pauseStopwatch", "lapStopwatch", "resetStopwatch", "getStopwatchStatus"),
        requiredPermissionLabels = emptyList()
    )

    override val supportedOperations: Set<String> = setOf(
        "startstopwatch", "pausestopwatch", "lapstopwatch",
        "resetstopwatch", "getstopwatchstatus", "clock_stopwatch"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        val action = when (operation.lowercase(Locale.US)) {
            "startstopwatch" -> "start"
            "pausestopwatch" -> "pause"
            "lapstopwatch" -> "lap"
            "resetstopwatch" -> "reset"
            "getstopwatchstatus" -> "status"
            else -> args.str("action") ?: "status"
        }
        val res = clockTools.controlStopwatch(action)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = operation,
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.ClockStopwatchPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                success = res.success
            )
        )
    }
}

/**
 * Android Calendar implementation of [CalendarManagementTool] backed by Room + CalendarContract.
 */
class AndroidCalendarTool(
    private val calendarTools: CalendarToolsManager
) : CalendarManagementTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_CALENDAR,
        displayName = "Calendar Events",
        category = "Schedule · CalendarContract",
        description = "Creates, updates, deletes, lists, and syncs calendar events to Android Calendar.",
        operations = listOf("addEvent", "updateEvent", "deleteEvent", "listEvents"),
        requiredPermissionLabels = listOf("Calendar")
    )

    override val supportedOperations: Set<String> = setOf(
        "addevent", "createevent", "calendar_create_event",
        "updateevent", "calendar_update_event",
        "deleteevent", "calendar_delete_event",
        "listevents", "calendar_list_events"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        return when (operation.lowercase(Locale.US)) {
            "addevent", "createevent", "calendar_create_event" -> addEvent(
                title = args.str("title") ?: "New Event",
                dateIso = args.str("date") ?: args.str("dateIso"),
                startTime = args.str("startTime") ?: args.str("time"),
                endTime = args.str("endTime"),
                location = args.str("location").orEmpty(),
                notes = args.str("notes") ?: args.str("description").orEmpty()
            )
            "updateevent", "calendar_update_event" -> updateEvent(
                query = args.str("query") ?: args.str("id") ?: args.str("eventId") ?: args.str("title"),
                newTitle = args.str("newTitle") ?: args.str("title"),
                newDateIso = args.str("date") ?: args.str("dateIso"),
                newStartTime = args.str("startTime") ?: args.str("time"),
                newEndTime = args.str("endTime"),
                newLocation = args.str("location"),
                newNotes = args.str("notes") ?: args.str("description")
            )
            "deleteevent", "calendar_delete_event" -> deleteEvent(
                query = args.str("query") ?: args.str("id") ?: args.str("eventId") ?: args.str("title")
            )
            "listevents", "calendar_list_events" -> listEvents(
                dateFilter = args.str("date") ?: args.str("dateIso")
            )
            else -> GenUiToolResult(
                toolId = descriptor.id,
                operation = operation,
                success = false,
                feedback = "Unsupported calendar operation: $operation",
                uiPart = null
            )
        }
    }

    override suspend fun addEvent(
        title: String,
        dateIso: String?,
        startTime: String?,
        endTime: String?,
        location: String,
        notes: String
    ): GenUiToolResult {
        val res = calendarTools.addEvent(title, dateIso, startTime, endTime, location, notes)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "addEvent",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.CalendarEventsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightEventId = res.targetEventId,
                success = res.success,
                calendarSyncMode = res.calendarSyncMode
            )
        )
    }

    override suspend fun updateEvent(
        query: String?,
        newTitle: String?,
        newDateIso: String?,
        newStartTime: String?,
        newEndTime: String?,
        newLocation: String?,
        newNotes: String?
    ): GenUiToolResult {
        val res = calendarTools.updateEvent(query, newTitle, newDateIso, newStartTime, newEndTime, newLocation, newNotes)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "updateEvent",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.CalendarEventsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightEventId = res.targetEventId,
                success = res.success,
                calendarSyncMode = res.calendarSyncMode
            )
        )
    }

    override suspend fun deleteEvent(query: String?): GenUiToolResult {
        val res = calendarTools.deleteEvent(query)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "deleteEvent",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.CalendarEventsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightEventId = null,
                success = res.success,
                calendarSyncMode = res.calendarSyncMode
            )
        )
    }

    override suspend fun listEvents(dateFilter: String?): GenUiToolResult {
        val res = calendarTools.listEvents(dateFilter)
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = "listEvents",
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.CalendarEventsPanel(
                partId = nextPartId(),
                actionSummary = res.summary,
                highlightEventId = null,
                success = res.success,
                calendarSyncMode = res.calendarSyncMode
            )
        )
    }
}

/**
 * Inline Generative UI Widget renderer tool (`render_gen_ui`).
 */
class GenUiWidgetTool : GenUiTool {
    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_GEN_UI_WIDGETS,
        displayName = "Generative UI Widgets",
        category = "Interactive UI",
        description = "Renders native Compose charts, calendars, checklists, tables, quizzes, and device controls inline.",
        operations = listOf("render_gen_ui"),
        requiredPermissionLabels = listOf("Write Settings", "Do Not Disturb")
    )

    override val supportedOperations: Set<String> = setOf("render_gen_ui")

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        val spec = ChatGenUiToolProtocol.decodeGenUiSpec(args)
            ?: return GenUiToolResult(
                toolId = descriptor.id,
                operation = operation,
                success = false,
                feedback = "Invalid Generative UI widget payload.",
                uiPart = null
            )
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = operation,
            success = true,
            feedback = "Rendered '${spec.title}' (${spec.widgets.size} widget${if (spec.widgets.size == 1) "" else "s"})",
            uiPart = InChatUiPart.GenUiCardPart(nextPartId(), spec)
        )
    }
}

/**
 * Camera & Flashlight tool backed by real Android Camera intents and Camera2 Torch API.
 */
class CameraControlTool(
    private val deviceTools: com.mote.app.control.DeviceAutomationToolsManager
) : GenUiTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_CAMERA,
        displayName = "Camera & Flashlight",
        category = "Hardware · Camera2",
        description = "Launches photo or video camera and toggles the device hardware flashlight in real time.",
        operations = listOf("openCamera", "takePhoto", "openVideoCamera", "toggleFlashlight"),
        requiredPermissionLabels = listOf("Camera")
    )

    override val supportedOperations: Set<String> = setOf(
        "opencamera", "camera", "launchcamera",
        "takephoto", "capturephoto", "camera_photo",
        "openvideocamera", "recordvideo", "camera_video",
        "toggleflashlight", "flashlight", "torch", "setflashlight"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        val op = operation.lowercase(Locale.US)
        val res = when (op) {
            "toggleflashlight", "flashlight", "torch", "setflashlight" -> {
                val enabled = args.boolVal("enabled") ?: args.str("state")?.let { s ->
                    when (s.lowercase(Locale.US)) {
                        "on", "true", "enable" -> true
                        "off", "false", "disable" -> false
                        else -> null
                    }
                }
                deviceTools.toggleFlashlight(enabled)
            }
            "openvideocamera", "recordvideo", "camera_video" -> {
                deviceTools.openCamera(mode = "video", facing = args.str("facing"))
            }
            else -> {
                deviceTools.openCamera(
                    mode = args.str("mode") ?: "photo",
                    facing = args.str("facing")
                )
            }
        }
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = operation,
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.DeviceActionPanel(
                partId = nextPartId(),
                category = "CAMERA",
                title = res.title,
                summary = res.summary,
                badge = res.badge,
                success = res.success,
                detailLines = res.detailLines
            )
        )
    }
}

/**
 * MediaProjection Screen Capture tool: takes explicit user-authorized screenshots via Android's
 * MediaProjection API and surfaces the resulting Bitmap inline in chat for visual analysis.
 */
class ScreenCaptureTool(
    private val deviceTools: com.mote.app.control.DeviceAutomationToolsManager
) : GenUiTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_SCREEN_CAPTURE,
        displayName = "Screen Capture (MediaProjection)",
        category = "Display · MediaProjection API",
        description = "Takes explicit user-authorized MediaProjection screenshots and surfaces the bitmap in chat for AI visual analysis.",
        operations = listOf("takeScreenshot", "captureAndAnalyzeScreen", "captureScreenContext"),
        requiredPermissionLabels = listOf("MediaProjection Consent")
    )

    override val supportedOperations: Set<String> = setOf(
        "takescreenshot", "screenshot", "capturescreen", "screen_capture",
        "captureandanalyzescreen", "analyzescreen", "capturescreenforanalysis",
        "capturescreencontext", "readscreen", "screen_context"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        val op = operation.lowercase(Locale.US)
        val wantsAnalysis = op in setOf("captureandanalyzescreen", "analyzescreen", "capturescreenforanalysis") ||
            (args.boolVal("analyze") == true)
        val analysisPrompt = if (wantsAnalysis) {
            args.str("analysisPrompt")
                ?: args.str("prompt")
                ?: "Visually analyze this captured screen: summarize what is displayed, read key text, and suggest useful next actions."
        } else {
            null
        }

        val res = if (op == "capturescreencontext" || op == "readscreen" || op == "screen_context") {
            deviceTools.captureScreenContext()
        } else {
            deviceTools.takeScreenshot(label = args.str("label") ?: args.str("name"))
        }
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = operation,
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.ScreenCapturePanel(
                partId = nextPartId(),
                title = res.title,
                summary = res.summary,
                badge = res.badge,
                success = res.success,
                captureId = res.captureId,
                savedUriString = res.savedUriString,
                detailLines = res.detailLines,
                autoAnalyzePrompt = if (res.success && res.captureId != null) analysisPrompt else null
            )
        )
    }
}

/**
 * App Navigation Tool using Android UsageStatsManager and Intent-based launching to open
 * common & installed apps (Chrome, YouTube, Gmail, Maps, WhatsApp, Spotify, recent apps, etc.),
 * gated by Mote's tool permissions system.
 */
class AppNavigationTool(
    private val deviceTools: com.mote.app.control.DeviceAutomationToolsManager
) : GenUiTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_APP_LAUNCHER,
        displayName = "App Navigation Tool",
        category = "Apps · UsageStats + Intents",
        description = "Opens Chrome, YouTube, Gmail, Maps, WhatsApp, Spotify, and any installed or recently used app via UsageStats & Intents.",
        operations = listOf("openApp", "openRecentApp", "getRecentApps", "listInstalledApps"),
        requiredPermissionLabels = listOf("App Usage Access")
    )

    override val supportedOperations: Set<String> = setOf(
        "openapp", "launchapp", "startapp", "app_open", "navigateapp", "open_application",
        "openrecentapp", "switchlastapp", "lastapp", "recentapp",
        "getrecentapps", "recentapps", "appusage", "usagestats",
        "listinstalledapps", "listapps", "findapp"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        val op = operation.lowercase(Locale.US)
        val res = when (op) {
            "openrecentapp", "switchlastapp", "lastapp", "recentapp" -> {
                deviceTools.openRecentApp(index = args.intVal("index") ?: 0)
            }
            "getrecentapps", "recentapps", "appusage", "usagestats" -> {
                deviceTools.getRecentApps(limit = args.intVal("limit") ?: 8)
            }
            "listinstalledapps", "listapps", "findapp" -> {
                deviceTools.listInstalledApps(args.str("query") ?: args.str("filter"))
            }
            else -> {
                val target = args.str("appName")
                    ?: args.str("app")
                    ?: args.str("name")
                    ?: args.str("packageName")
                    ?: args.str("query")
                    .orEmpty()
                deviceTools.openApp(target)
            }
        }
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = operation,
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.DeviceActionPanel(
                partId = nextPartId(),
                category = "APPS",
                title = res.title,
                summary = res.summary,
                badge = res.badge,
                success = res.success,
                detailLines = res.detailLines
            )
        )
    }
}

/**
 * Device, Maps, Settings, URL, and In-App Navigation tool.
 */
class DeviceNavigationTool(
    private val deviceTools: com.mote.app.control.DeviceAutomationToolsManager
) : GenUiTool {

    override val descriptor = RegisteredToolDescriptor(
        id = GenUiToolRegistry.TOOL_NAVIGATION,
        displayName = "Device & Maps Navigation",
        category = "Navigation · System",
        description = "Executes Android Back/Home/Recents/Notifications, Maps turn-by-turn, URLs, and Settings jumps.",
        operations = listOf("navigateSystem", "navigateMaps", "openUrl", "searchWeb", "openSettings", "navigateAppTab"),
        requiredPermissionLabels = listOf("Accessibility (Global Keys)")
    )

    override val supportedOperations: Set<String> = setOf(
        "navigatesystem", "systemnavigation", "globalaction", "goback", "gohome",
        "navigatemaps", "directions", "openmaps",
        "openurl", "openwebsite", "browse",
        "searchweb", "websearch",
        "opensettings", "settings",
        "navigateapptab", "switchtab"
    )

    override suspend fun execute(operation: String, args: JsonObject): GenUiToolResult {
        val op = operation.lowercase(Locale.US)
        val res = when (op) {
            "goback" -> deviceTools.navigateSystem("back")
            "gohome" -> deviceTools.navigateSystem("home")
            "navigatesystem", "systemnavigation", "globalaction" ->
                deviceTools.navigateSystem(args.str("action") ?: args.str("direction") ?: "home")
            "navigatemaps", "directions", "openmaps" ->
                deviceTools.navigateMaps(
                    destination = args.str("destination") ?: args.str("query") ?: args.str("address").orEmpty(),
                    mode = args.str("mode")
                )
            "openurl", "openwebsite", "browse" ->
                deviceTools.openUrl(args.str("url") ?: args.str("link").orEmpty())
            "searchweb", "websearch" ->
                deviceTools.searchWeb(args.str("query") ?: args.str("q").orEmpty())
            "opensettings", "settings" ->
                deviceTools.openSettings(args.str("section") ?: args.str("screen") ?: "main")
            "navigateapptab", "switchtab" ->
                deviceTools.navigateAppTab(args.str("tab") ?: args.str("screen") ?: "chat")
            else -> deviceTools.navigateSystem(args.str("action") ?: "home")
        }
        return GenUiToolResult(
            toolId = descriptor.id,
            operation = operation,
            success = res.success,
            feedback = res.summary,
            uiPart = InChatUiPart.DeviceActionPanel(
                partId = nextPartId(),
                category = "NAVIGATION",
                title = res.title,
                summary = res.summary,
                badge = res.badge,
                success = res.success,
                detailLines = res.detailLines
            )
        )
    }
}

/**
 * Central Generative UI Tool Registry coordinating:
 * - Tool registration (AlarmManager, CountdownTimer, Stopwatch, Calendar, Camera, Screenshot, App Launcher, Navigation, GenUI)
 * - Explicit user authorization enforcement from Room preferences
 * - Room-persisted tool execution logs & aggregated usage statistics
 */
class GenUiToolRegistry(
    clockTools: ClockToolsManager,
    calendarTools: CalendarToolsManager,
    deviceTools: com.mote.app.control.DeviceAutomationToolsManager,
    private val dao: LocalModelDao,
    private val scope: CoroutineScope,
    private val isToolEnabledProvider: (String) -> Boolean
) {
    val alarmTool: AlarmManagementTool = AndroidAlarmManagerTool(clockTools)
    val timerTool: CountdownTimerTool = CountdownTimerTool(clockTools)
    val stopwatchTool: StopwatchTool = StopwatchTool(clockTools)
    val calendarTool: CalendarManagementTool = AndroidCalendarTool(calendarTools)
    val cameraTool: CameraControlTool = CameraControlTool(deviceTools)
    val screenCaptureTool: ScreenCaptureTool = ScreenCaptureTool(deviceTools)
    val appNavigationTool: AppNavigationTool = AppNavigationTool(deviceTools)
    val navigationTool: DeviceNavigationTool = DeviceNavigationTool(deviceTools)
    val genUiWidgetTool: GenUiWidgetTool = GenUiWidgetTool()

    private val tools: List<GenUiTool> = listOf(
        alarmTool,
        timerTool,
        stopwatchTool,
        calendarTool,
        cameraTool,
        screenCaptureTool,
        appNavigationTool,
        navigationTool,
        genUiWidgetTool
    )

    val auditLog: StateFlow<List<ToolExecutionAuditEntry>> = dao.observeAllToolLogs()
        .map { rows ->
            rows.map { r ->
                ToolExecutionAuditEntry(
                    id = r.id,
                    timestampMs = r.timestampMs,
                    toolId = r.toolId,
                    operation = r.operation,
                    success = r.success,
                    blocked = r.blocked,
                    feedback = r.feedback
                )
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val usageStats: StateFlow<ToolUsageStatsSnapshot> = auditLog
        .map { logs -> computeUsageStats(logs) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = computeUsageStats(emptyList())
        )

    fun registeredDescriptors(): List<RegisteredToolDescriptor> = tools.map { it.descriptor }

    fun enabledToolIds(prefs: ModelStudioPrefsEntity): Set<String> = buildSet {
        if (prefs.toolAlarmEnabled) add(TOOL_ALARM_MANAGER)
        if (prefs.toolTimerEnabled) add(TOOL_COUNTDOWN_TIMER)
        if (prefs.toolStopwatchEnabled) add(TOOL_STOPWATCH)
        if (prefs.toolCalendarEnabled) add(TOOL_CALENDAR)
        if (prefs.toolCameraEnabled) add(TOOL_CAMERA)
        if (prefs.toolScreenCaptureEnabled) add(TOOL_SCREEN_CAPTURE)
        if (prefs.toolAppLauncherEnabled) add(TOOL_APP_LAUNCHER)
        if (prefs.toolNavigationEnabled) add(TOOL_NAVIGATION)
        if (prefs.toolGenUiEnabled) add(TOOL_GEN_UI_WIDGETS)
    }

    suspend fun dispatch(invocation: ChatToolInvocation): GenUiToolResult? {
        val opKey = invocation.tool.trim().lowercase(Locale.US)
        val matchedTool = tools.firstOrNull { opKey in it.supportedOperations } ?: return null

        if (!isToolEnabledProvider(matchedTool.descriptor.id)) {
            val blockedMsg = "${matchedTool.descriptor.displayName} is unauthorized. Enable it in Settings → Tool & Model Permissions."
            recordAudit(
                toolId = matchedTool.descriptor.id,
                operation = invocation.tool,
                success = false,
                blocked = true,
                feedback = blockedMsg
            )
            return GenUiToolResult(
                toolId = matchedTool.descriptor.id,
                operation = invocation.tool,
                success = false,
                feedback = blockedMsg,
                uiPart = InChatUiPart.ToolStatusBanner(
                    partId = nextPartId(),
                    toolName = matchedTool.descriptor.displayName,
                    operation = invocation.tool,
                    success = false,
                    message = blockedMsg
                )
            )
        }

        val result = matchedTool.execute(invocation.tool, invocation.args)
        recordAudit(
            toolId = result.toolId,
            operation = result.operation,
            success = result.success,
            blocked = false,
            feedback = result.feedback
        )
        return result
    }

    fun clearAuditLog() {
        scope.launch { dao.clearAllToolLogs() }
    }

    private suspend fun recordAudit(
        toolId: String,
        operation: String,
        success: Boolean,
        blocked: Boolean,
        feedback: String
    ) {
        dao.insertToolLog(
            ToolExecutionLogEntity(
                id = "audit_${System.nanoTime()}",
                timestampMs = System.currentTimeMillis(),
                toolId = toolId,
                operation = operation,
                success = success,
                blocked = blocked,
                feedback = feedback
            )
        )
    }

    private fun computeUsageStats(logs: List<ToolExecutionAuditEntry>): ToolUsageStatsSnapshot {
        val total = logs.size
        val succeeded = logs.count { it.success }
        val blocked = logs.count { it.blocked }
        val byTool = logs.groupBy { it.toolId }
        val maxCalls = tools.maxOfOrNull { byTool[it.descriptor.id]?.size ?: 0 }?.coerceAtLeast(1) ?: 1

        val buckets = tools.map { tool ->
            val group = byTool[tool.descriptor.id].orEmpty()
            ToolUsageBucket(
                toolId = tool.descriptor.id,
                displayName = tool.descriptor.displayName,
                totalCalls = group.size,
                successCalls = group.count { it.success },
                blockedCalls = group.count { it.blocked },
                shareFraction = if (group.isEmpty()) 0f else (group.size.toFloat() / maxCalls.toFloat()).coerceIn(0.08f, 1f)
            )
        }.sortedByDescending { it.totalCalls }

        val topName = buckets.firstOrNull { it.totalCalls > 0 }?.displayName
        return ToolUsageStatsSnapshot(
            totalCalls = total,
            successfulCalls = succeeded,
            blockedCalls = blocked,
            topToolName = topName,
            buckets = buckets
        )
    }

    companion object {
        const val TOOL_ALARM_MANAGER = "alarm_manager"
        const val TOOL_COUNTDOWN_TIMER = "countdown_timer"
        const val TOOL_STOPWATCH = "stopwatch"
        const val TOOL_CALENDAR = "calendar_manager"
        const val TOOL_CAMERA = "camera_control"
        const val TOOL_SCREEN_CAPTURE = "screen_capture"
        const val TOOL_APP_LAUNCHER = "app_launcher"
        const val TOOL_NAVIGATION = "device_navigation"
        const val TOOL_GEN_UI_WIDGETS = "gen_ui_widgets"

        val ALL_TOOL_IDS: Set<String> = setOf(
            TOOL_ALARM_MANAGER,
            TOOL_COUNTDOWN_TIMER,
            TOOL_STOPWATCH,
            TOOL_CALENDAR,
            TOOL_CAMERA,
            TOOL_SCREEN_CAPTURE,
            TOOL_APP_LAUNCHER,
            TOOL_NAVIGATION,
            TOOL_GEN_UI_WIDGETS
        )
    }
}

private fun nextPartId(): String = "part_${System.nanoTime()}"
