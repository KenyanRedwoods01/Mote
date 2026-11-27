package com.mote.app.control

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.mote.app.data.db.CalendarEventEntity
import com.mote.app.data.db.LocalModelDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class CalendarToolExecutionOutcome(
    val summary: String,
    val success: Boolean = true,
    val targetEventId: String? = null,
    val calendarSyncMode: String? = null
)

/**
 * Full Android Calendar Tools controller powered by the Android Calendar Provider API
 * (`CalendarContract.Events` / `CalendarContract.Calendars`) + Room offline cache.
 *
 * When `READ_CALENDAR` / `WRITE_CALENDAR` are granted:
 * - `addEvent` inserts directly into `CalendarContract.Events.CONTENT_URI` and stores `androidProviderEventId`.
 * - `listEvents` queries live events from `CalendarContract.Events.CONTENT_URI` and syncs them with Room.
 * - `updateEvent` updates the matching event directly in Android's Calendar Provider.
 * - `deleteEvent` deletes the event directly from Android's Calendar Provider and Room.
 */
class CalendarToolsManager(context: Context) {

    private val appContext = context.applicationContext
    private val dao = LocalModelDatabase.getInstance(appContext).localModelDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val events: StateFlow<List<CalendarEventEntity>> = dao.observeAllCalendarEvents()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun hasCalendarReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun hasCalendarWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            hasCalendarReadPermission()

    suspend fun syncFromAndroidCalendarProvider(): Int = withContext(Dispatchers.IO) {
        if (!hasCalendarReadPermission()) return@withContext 0
        try {
            val resolver = appContext.contentResolver
            val nowMs = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            val horizonMs = nowMs + 60L * 24L * 60L * 60L * 1000L
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            )
            val selection = "${CalendarContract.Events.DELETED} != 1 AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(nowMs.toString(), horizonMs.toString())

            val existing = dao.getAllCalendarEventsOnce()
            val byProviderId = existing.mapNotNull { ev -> ev.androidProviderEventId?.let { it to ev } }.toMap()
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
            var importedCount = 0

            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && importedCount < 30) {
                    val providerId = cursor.getLong(0)
                    val title = cursor.getString(1)?.trim().orEmpty().ifBlank { "Untitled Event" }
                    val startMs = cursor.getLong(2)
                    val rawEndMs = cursor.getLong(3)
                    val endMs = if (rawEndMs > startMs) rawEndMs else startMs + 60L * 60L * 1000L
                    val location = cursor.getString(4)?.trim().orEmpty().take(80)
                    val notes = cursor.getString(5)?.trim().orEmpty().take(240)

                    val existingRow = byProviderId[providerId]
                    val entity = CalendarEventEntity(
                        id = existingRow?.id ?: "sys_cal_$providerId",
                        title = title.take(80),
                        dateIso = dateFmt.format(Date(startMs)),
                        startTime = timeFmt.format(Date(startMs)),
                        endTime = timeFmt.format(Date(endMs)),
                        location = location,
                        notes = notes,
                        syncedToSystemCalendar = true,
                        androidProviderEventId = providerId,
                        createdAtEpochMs = existingRow?.createdAtEpochMs ?: startMs
                    )
                    dao.upsertCalendarEvent(entity)
                    importedCount++
                }
            }
            importedCount
        } catch (_: Exception) {
            0
        }
    }

    suspend fun addEvent(
        title: String,
        dateIso: String?,
        startTime: String?,
        endTime: String?,
        location: String = "",
        notes: String = ""
    ): CalendarToolExecutionOutcome = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim().ifBlank { "New Event" }.take(80)
        val resolvedDate = normalizeDateIso(dateIso)
        val resolvedStart = normalizeTimeHm(startTime) ?: "09:00"
        val resolvedEnd = normalizeTimeHm(endTime) ?: addOneHour(resolvedStart)
        val id = "cal_${UUID.randomUUID().toString().take(8)}"

        val providerEventId = tryInsertIntoSystemCalendarProvider(
            title = cleanTitle,
            dateIso = resolvedDate,
            startTime = resolvedStart,
            endTime = resolvedEnd,
            location = location.trim(),
            notes = notes.trim()
        )
        val syncedToProvider = providerEventId != null

        val entity = CalendarEventEntity(
            id = id,
            title = cleanTitle,
            dateIso = resolvedDate,
            startTime = resolvedStart,
            endTime = resolvedEnd,
            location = location.trim().take(80),
            notes = notes.trim().take(240),
            syncedToSystemCalendar = syncedToProvider,
            androidProviderEventId = providerEventId,
            createdAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertCalendarEvent(entity)

        val mode = if (syncedToProvider) "Android Calendar Provider" else "Local Room Schedule"
        val suffix = if (syncedToProvider) " · synced to Android Calendar" else " · saved locally (grant Calendar permission to sync)"
        CalendarToolExecutionOutcome(
            summary = "Confirmed: Created '$cleanTitle' on $resolvedDate ($resolvedStart–$resolvedEnd)$suffix",
            success = true,
            targetEventId = id,
            calendarSyncMode = mode
        )
    }

    suspend fun updateEvent(
        queryOrId: String?,
        newTitle: String?,
        newDateIso: String?,
        newStartTime: String?,
        newEndTime: String?,
        newLocation: String?,
        newNotes: String?
    ): CalendarToolExecutionOutcome = withContext(Dispatchers.IO) {
        if (hasCalendarReadPermission()) {
            syncFromAndroidCalendarProvider()
        }
        val current = dao.getAllCalendarEventsOnce()
        if (current.isEmpty()) {
            return@withContext if (!newTitle.isNullOrBlank()) {
                addEvent(newTitle, newDateIso, newStartTime, newEndTime, newLocation.orEmpty(), newNotes.orEmpty())
            } else {
                CalendarToolExecutionOutcome("No calendar events found to update.", success = false)
            }
        }
        val target = findMatchingEvent(current, queryOrId) ?: current.last()
        val updatedStart = newStartTime?.let { normalizeTimeHm(it) } ?: target.startTime
        val updated = target.copy(
            title = newTitle?.trim()?.takeIf { it.isNotBlank() }?.take(80) ?: target.title,
            dateIso = newDateIso?.takeIf { it.isNotBlank() }?.let { normalizeDateIso(it) } ?: target.dateIso,
            startTime = updatedStart,
            endTime = newEndTime?.let { normalizeTimeHm(it) } ?: target.endTime,
            location = newLocation?.trim()?.take(80) ?: target.location,
            notes = newNotes?.trim()?.take(240) ?: target.notes
        )

        val updatedInProvider = updated.androidProviderEventId?.let { providerId ->
            tryUpdateInSystemCalendarProvider(providerId, updated)
        } ?: false

        dao.upsertCalendarEvent(updated.copy(syncedToSystemCalendar = updated.syncedToSystemCalendar || updatedInProvider))
        val mode = if (updatedInProvider) "Android Calendar Provider" else "Local Room Schedule"
        CalendarToolExecutionOutcome(
            summary = "Confirmed: Updated '${updated.title}' to ${updated.dateIso} (${updated.startTime}–${updated.endTime})",
            success = true,
            targetEventId = updated.id,
            calendarSyncMode = mode
        )
    }

    suspend fun deleteEvent(queryOrId: String?): CalendarToolExecutionOutcome = withContext(Dispatchers.IO) {
        if (hasCalendarReadPermission()) {
            syncFromAndroidCalendarProvider()
        }
        val current = dao.getAllCalendarEventsOnce()
        if (current.isEmpty()) {
            return@withContext CalendarToolExecutionOutcome("No calendar events found to delete.", success = false)
        }
        if (queryOrId.equals("all", ignoreCase = true)) {
            var providerDeletes = 0
            current.forEach { ev ->
                if (ev.androidProviderEventId != null && tryDeleteFromSystemCalendarProvider(ev.androidProviderEventId)) {
                    providerDeletes++
                }
            }
            dao.deleteAllCalendarEvents()
            val detail = if (providerDeletes > 0) " ($providerDeletes removed from Android Calendar)" else ""
            return@withContext CalendarToolExecutionOutcome(
                summary = "Confirmed: Cleared all ${current.size} calendar event(s)$detail",
                success = true,
                calendarSyncMode = if (providerDeletes > 0) "Android Calendar Provider" else "Removed"
            )
        }
        val target = findMatchingEvent(current, queryOrId) ?: current.last()
        val deletedFromProvider = target.androidProviderEventId?.let {
            tryDeleteFromSystemCalendarProvider(it)
        } ?: false
        dao.deleteCalendarEventById(target.id)
        val suffix = if (deletedFromProvider) " from Android Calendar Provider" else ""
        CalendarToolExecutionOutcome(
            summary = "Confirmed: Deleted event '${target.title}' (${target.dateIso})$suffix",
            success = true,
            calendarSyncMode = if (deletedFromProvider) "Android Calendar Provider" else "Removed"
        )
    }

    fun deleteEventById(eventId: String) {
        scope.launch {
            val target = dao.getAllCalendarEventsOnce().firstOrNull { it.id == eventId }
            target?.androidProviderEventId?.let { tryDeleteFromSystemCalendarProvider(it) }
            dao.deleteCalendarEventById(eventId)
        }
    }

    suspend fun listEvents(dateFilter: String? = null): CalendarToolExecutionOutcome = withContext(Dispatchers.IO) {
        val syncedCount = if (hasCalendarReadPermission()) syncFromAndroidCalendarProvider() else 0
        val all = dao.getAllCalendarEventsOnce()
        val filtered = if (dateFilter.isNullOrBlank()) {
            all
        } else {
            val normalized = normalizeDateIso(dateFilter)
            all.filter { it.dateIso == normalized }
        }
        val summary = if (filtered.isEmpty()) {
            if (dateFilter.isNullOrBlank()) "No calendar events scheduled yet." else "No events found for $dateFilter."
        } else {
            val providerNote = if (syncedCount > 0) " (synced with Android Calendar Provider)" else ""
            "Showing ${filtered.size} scheduled event${if (filtered.size == 1) "" else "s"}$providerNote"
        }
        CalendarToolExecutionOutcome(
            summary = summary,
            success = true,
            calendarSyncMode = if (hasCalendarWritePermission()) "Android Calendar Provider" else "Local Room Schedule"
        )
    }

    fun syncEventToAndroidCalendar(event: CalendarEventEntity): Boolean {
        return try {
            val beginMs = parseDateTimeMillis(event.dateIso, event.startTime)
            val endMs = parseDateTimeMillis(event.dateIso, event.endTime).coerceAtLeast(beginMs + 30 * 60_000L)
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, event.title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                if (event.location.isNotBlank()) {
                    putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
                }
                if (event.notes.isNotBlank()) {
                    putExtra(CalendarContract.Events.DESCRIPTION, event.notes)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun buildCalendarContextForAi(): String {
        val sb = StringBuilder()
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        sb.appendLine("Today's Date: $todayIso")
        sb.appendLine("Android Calendar Provider Access: ${if (hasCalendarWritePermission()) "GRANTED (read/write)" else "ROOM_ONLY"}")
        val list = events.value
        if (list.isEmpty()) {
            sb.appendLine("Calendar Events: none scheduled.")
        } else {
            sb.appendLine("Current Calendar Events (${list.size}):")
            list.take(15).forEach { ev ->
                val loc = ev.location.takeIf { it.isNotBlank() }?.let { ", location=\"$it\"" }.orEmpty()
                val synced = if (ev.syncedToSystemCalendar) ", synced=AndroidCalendar" else ""
                sb.appendLine("- id=${ev.id}, date=${ev.dateIso}, time=${ev.startTime}-${ev.endTime}, title=\"${ev.title}\"$loc$synced")
            }
        }
        return sb.toString().trim()
    }

    private fun resolvePrimaryWritableCalendarId(): Long? {
        if (!hasCalendarReadPermission()) return null
        val resolver = appContext.contentResolver
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        // 1. Prefer visible primary writable calendar
        try {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        } catch (_: Exception) {
            // Fall through to broader query
        }

        // 2. Fallback to any writable calendar (even if hidden or non-primary)
        try {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        } catch (_: Exception) {
            // Fall through to local calendar creation
        }

        // 3. Auto-provision a real local Android Calendar account when device has no synced calendar (e.g., fresh emulator)
        return tryCreateLocalCalendarAccount()
    }

    private fun tryCreateLocalCalendarAccount(): Long? {
        if (!hasCalendarWritePermission()) return null
        return try {
            val accountName = "MoteLocalCalendar"
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, "Mote Calendar")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Mote AI Calendar")
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2563EB.toInt())
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            }
            val inserted = appContext.contentResolver.insert(uri, values)
            inserted?.let { ContentUris.parseId(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryInsertIntoSystemCalendarProvider(
        title: String,
        dateIso: String,
        startTime: String,
        endTime: String,
        location: String,
        notes: String
    ): Long? {
        if (!hasCalendarWritePermission()) return null
        return try {
            val calId = resolvePrimaryWritableCalendarId() ?: return null
            val beginMs = parseDateTimeMillis(dateIso, startTime)
            val endMs = parseDateTimeMillis(dateIso, endTime).coerceAtLeast(beginMs + 30 * 60_000L)
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, beginMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                if (notes.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, notes)
            }
            val insertedUri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            insertedUri?.let { ContentUris.parseId(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryUpdateInSystemCalendarProvider(
        providerEventId: Long,
        updated: CalendarEventEntity
    ): Boolean {
        if (!hasCalendarWritePermission()) return false
        return try {
            val beginMs = parseDateTimeMillis(updated.dateIso, updated.startTime)
            val endMs = parseDateTimeMillis(updated.dateIso, updated.endTime).coerceAtLeast(beginMs + 30 * 60_000L)
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, updated.title)
                put(CalendarContract.Events.DTSTART, beginMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_LOCATION, updated.location)
                put(CalendarContract.Events.DESCRIPTION, updated.notes)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, providerEventId)
            appContext.contentResolver.update(uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun tryDeleteFromSystemCalendarProvider(providerEventId: Long): Boolean {
        if (!hasCalendarWritePermission()) return false
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, providerEventId)
            appContext.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun findMatchingEvent(list: List<CalendarEventEntity>, query: String?): CalendarEventEntity? {
        val q = query?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        return list.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: list.firstOrNull { it.title.lowercase(Locale.US).contains(q) }
            ?: list.firstOrNull { it.dateIso.contains(q) }
            ?: list.firstOrNull { it.location.lowercase(Locale.US).contains(q) }
    }

    private fun normalizeDateIso(raw: String?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val clean = raw?.trim()?.lowercase(Locale.US).orEmpty()
        val cal = Calendar.getInstance()
        if (clean.isBlank() || clean == "today") return fmt.format(cal.time)
        if (clean == "tomorrow") {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            return fmt.format(cal.time)
        }
        if (clean == "day after tomorrow" || clean == "overmorrow") {
            cal.add(Calendar.DAY_OF_YEAR, 2)
            return fmt.format(cal.time)
        }
        Regex("(\\d{4}-\\d{2}-\\d{2})").find(clean)?.let { return it.groupValues[1] }

        // Handle weekday names like "monday", "next friday", "this wednesday"
        val dayMap = mapOf(
            "sunday" to Calendar.SUNDAY,
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY
        )
        dayMap.entries.firstOrNull { clean.contains(it.key) }?.let { (_, targetDay) ->
            var delta = (targetDay - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (delta == 0) delta = 7
            cal.add(Calendar.DAY_OF_YEAR, delta)
            return fmt.format(cal.time)
        }

        // Handle "in N days"
        Regex("in\\s+(\\d{1,3})\\s+day").find(clean)?.let { m ->
            val days = m.groupValues[1].toIntOrNull()?.coerceIn(1, 365) ?: 1
            cal.add(Calendar.DAY_OF_YEAR, days)
            return fmt.format(cal.time)
        }

        return fmt.format(cal.time)
    }

    private fun normalizeTimeHm(raw: String?): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return null
        val hmMatch = Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(clean)
        if (hmMatch != null) {
            var h = hmMatch.groupValues[1].toIntOrNull() ?: return null
            val m = (hmMatch.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
            val amPm = hmMatch.groupValues[3].lowercase(Locale.US)
            if (amPm == "pm" && h in 1..11) h += 12
            if (amPm == "am" && h == 12) h = 0
            h = h.coerceIn(0, 23)
            return String.format(Locale.US, "%02d:%02d", h, m)
        }
        val hourOnlyMatch = Regex("\\b(\\d{1,2})\\s*(am|pm)\\b", RegexOption.IGNORE_CASE).find(clean)
        if (hourOnlyMatch != null) {
            var h = hourOnlyMatch.groupValues[1].toIntOrNull() ?: return null
            val amPm = hourOnlyMatch.groupValues[2].lowercase(Locale.US)
            if (amPm == "pm" && h in 1..11) h += 12
            if (amPm == "am" && h == 12) h = 0
            h = h.coerceIn(0, 23)
            return String.format(Locale.US, "%02d:00", h)
        }
        return null
    }

    private fun addOneHour(startHm: String): String {
        val parts = startHm.split(":")
        val startH = (parts.getOrNull(0)?.toIntOrNull() ?: 9).coerceIn(0, 23)
        val startM = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        if (startH >= 23) {
            return "23:59"
        }
        return String.format(Locale.US, "%02d:%02d", startH + 1, startM)
    }

    private fun parseDateTimeMillis(dateIso: String, timeHm: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            sdf.parse("$dateIso $timeHm")?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
