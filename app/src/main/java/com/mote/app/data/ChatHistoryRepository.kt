package com.mote.app.data

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.mote.app.R
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

interface ChatHistoryStore {
    val isCloudSynced: Boolean
    fun observeHistory(): Flow<List<ChatMessage>>
    suspend fun appendMessage(
        role: ChatRole,
        botId: String,
        botName: String,
        text: String,
        engineUsed: String = "NONE"
    ): Result<Unit>
    suspend fun clearHistory(): Result<Unit>
}

/**
 * Local in-memory chat history store for Guest Mode sessions. Never accesses Firestore without Auth.
 */
class GuestChatHistoryStore(
    private val guestSessionId: String = "guest_local"
) : ChatHistoryStore {

    override val isCloudSynced: Boolean = false

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    override fun observeHistory(): Flow<List<ChatMessage>> = _messages.asStateFlow()

    override suspend fun appendMessage(
        role: ChatRole,
        botId: String,
        botName: String,
        text: String,
        engineUsed: String
    ): Result<Unit> {
        val cleanText = text.trim().take(2000)
        if (cleanText.isEmpty()) return Result.failure(IllegalArgumentException("Message cannot be blank"))
        val entry = ChatMessage(
            id = "guest_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = guestSessionId,
            role = role.name,
            botId = botId.trim().ifBlank { "focus" }.take(128),
            botName = botName.trim().ifBlank { "Mote" }.take(50),
            text = cleanText,
            engineUsed = sanitizeEngine(engineUsed),
            createdAt = Timestamp.now()
        )
        _messages.value = (_messages.value + entry).takeLast(50)
        return Result.success(Unit)
    }

    override suspend fun clearHistory(): Result<Unit> {
        _messages.value = emptyList()
        return Result.success(Unit)
    }
}

/**
 * Firestore-backed chat history store scoped to /users/{uid}/chatHistory/{messageId}.
 */
class FirestoreChatHistoryRepository(private val db: FirebaseFirestore) : ChatHistoryStore {

    override val isCloudSynced: Boolean = true

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in before accessing Firestore chat history.")
    }

    override fun observeHistory(): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/chatHistory"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("chatHistory")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(50)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    override suspend fun appendMessage(
        role: ChatRole,
        botId: String,
        botName: String,
        text: String,
        engineUsed: String
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/chatHistory"
        return try {
            val uid = requireUserId()
            val cleanText = text.trim().take(2000)
            val cleanBotId = botId.trim().ifBlank { "focus" }.take(128)
            val cleanBotName = botName.trim().ifBlank { "Mote" }.take(50)
            require(cleanText.isNotEmpty()) { "Message text cannot be blank" }

            val docRef = db.collection("users")
                .document(uid)
                .collection("chatHistory")
                .document()
            docPath = docRef.path

            val payload = mapOf(
                "userId" to uid,
                "role" to role.name,
                "botId" to cleanBotId,
                "botName" to cleanBotName,
                "text" to cleanText,
                "engineUsed" to sanitizeEngine(engineUsed),
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun clearHistory(): Result<Unit> {
        var colPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/chatHistory"
        return try {
            val uid = requireUserId()
            val colRef = db.collection("users").document(uid).collection("chatHistory")
            colPath = colRef.path
            val snapshot = colRef.limit(100).get().await()
            if (!snapshot.isEmpty) {
                val batch = db.batch()
                snapshot.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, colPath)
            Result.failure(e)
        }
    }
}

private val ALLOWED_ENGINES = setOf("LOCAL_NANO", "CLOUD_FLASH", "OFFLINE_FALLBACK", "NONE")

private fun sanitizeEngine(raw: String): String =
    if (raw in ALLOWED_ENGINES) raw else "NONE"
