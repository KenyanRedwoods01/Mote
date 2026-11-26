package com.mote.app.control

import android.content.Context
import com.mote.app.data.db.LocalModelDatabase
import com.mote.app.data.db.SavedNoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class NoteToolOutcome(
    val success: Boolean,
    val summary: String,
    val noteId: String? = null,
    val title: String? = null,
    val contentPreview: String? = null
)

/**
 * Room-backed Notes manager supporting multi-step tool chains like:
 * `Take screenshot -> OCR analysis -> Save to Notes` with real persistent storage and
 * post-write background database verification.
 */
class NotesToolsManager(context: Context) {

    private val dao = LocalModelDatabase.getInstance(context.applicationContext).localModelDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val notes: StateFlow<List<SavedNoteEntity>> = dao.observeAllNotes()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    suspend fun saveNote(
        title: String?,
        content: String,
        sourceTag: String = "Mote AI"
    ): NoteToolOutcome {
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) {
            return NoteToolOutcome(
                success = false,
                summary = "Cannot save an empty note — provide text or run OCR analysis first."
            )
        }
        val finalTitle = title?.trim()?.takeIf { it.isNotBlank() }
            ?: "Note ${SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())}"
        val id = "note_${UUID.randomUUID().toString().take(8)}"
        val entity = SavedNoteEntity(
            id = id,
            title = finalTitle,
            content = cleanContent,
            sourceTag = sourceTag.ifBlank { "Mote AI" },
            createdAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertNote(entity)
        return NoteToolOutcome(
            success = true,
            summary = "Saved note '$finalTitle' (${cleanContent.length} chars) to Mote Notes.",
            noteId = id,
            title = finalTitle,
            contentPreview = cleanContent.take(180)
        )
    }

    suspend fun listNotes(): NoteToolOutcome {
        val all = dao.getAllNotesOnce()
        val summary = if (all.isEmpty()) {
            "No saved notes yet."
        } else {
            "Found ${all.size} saved note${if (all.size == 1) "" else "s"} in Room."
        }
        return NoteToolOutcome(
            success = true,
            summary = summary
        )
    }

    suspend fun deleteNote(query: String?): NoteToolOutcome {
        val all = dao.getAllNotesOnce()
        if (all.isEmpty()) {
            return NoteToolOutcome(false, "No saved notes to delete.")
        }
        val target = if (query.isNullOrBlank()) {
            all.first()
        } else {
            val q = query.trim().lowercase(Locale.US)
            all.firstOrNull {
                it.id.equals(q, ignoreCase = true) ||
                    it.title.lowercase(Locale.US).contains(q) ||
                    it.content.lowercase(Locale.US).contains(q)
            } ?: all.first()
        }
        dao.deleteNoteById(target.id)
        return NoteToolOutcome(
            success = true,
            summary = "Deleted note '${target.title}'.",
            noteId = target.id,
            title = target.title
        )
    }

    suspend fun deleteNoteById(noteId: String) {
        dao.deleteNoteById(noteId)
    }

    suspend fun verifyNotePersisted(noteId: String?): Boolean {
        if (noteId.isNullOrBlank()) return false
        val found = dao.getNoteById(noteId)
        return found != null && found.content.isNotBlank()
    }

    fun buildNotesContextForAi(): String {
        val list = notes.value
        if (list.isEmpty()) return "SAVED NOTES: none."
        val preview = list.take(4).joinToString("; ") { "'${it.title}' (${it.content.take(48)})" }
        return "SAVED NOTES (${list.size}): $preview"
    }
}
