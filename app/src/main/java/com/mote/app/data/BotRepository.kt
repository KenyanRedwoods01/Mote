package com.mote.app.data

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import com.mote.app.R
import com.mote.app.model.BotPersona
import com.mote.app.model.MoteInferenceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

interface BotStore {
    val isCloudSynced: Boolean
    fun observeBots(): Flow<List<BotPersona>>
    suspend fun seedDefaultBotsIfEmpty(): Result<Unit>
    suspend fun toggleBot(botId: String, currentActive: Boolean): Result<Unit>
    suspend fun upsertBot(bot: BotPersona, isExisting: Boolean): Result<Unit>
}

/**
 * Local in-memory bot store for Guest Mode. Never touches Firebase Auth or Firestore.
 */
class GuestBotStore : BotStore {
    override val isCloudSynced: Boolean = false

    private val _bots = MutableStateFlow(BotPersona.defaultStarters)

    override fun observeBots(): Flow<List<BotPersona>> = _bots.asStateFlow()

    override suspend fun seedDefaultBotsIfEmpty(): Result<Unit> = Result.success(Unit)

    override suspend fun toggleBot(botId: String, currentActive: Boolean): Result<Unit> {
        _bots.value = _bots.value.map { bot ->
            if (bot.id == botId) bot.copy(active = !currentActive) else bot
        }
        return Result.success(Unit)
    }

    override suspend fun upsertBot(bot: BotPersona, isExisting: Boolean): Result<Unit> {
        _bots.value = if (isExisting) {
            _bots.value.map { if (it.id == bot.id) bot else it }
        } else {
            _bots.value + bot
        }
        return Result.success(Unit)
    }
}

/**
 * Firestore-backed bot store scoped to /users/{uid}/bots/{botId}.
 */
class BotRepository(private val db: FirebaseFirestore) : BotStore {

    override val isCloudSynced: Boolean = true

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    override fun observeBots(): Flow<List<BotPersona>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/bots"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("bots")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        BotPersona::class.java,
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

    override suspend fun seedDefaultBotsIfEmpty(): Result<Unit> {
        var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/bots"
        return try {
            val uid = requireUserId()
            val botsCol = db.collection("users").document(uid).collection("bots")
            path = botsCol.path
            val existing = botsCol.limit(1).get().await()
            if (existing.isEmpty) {
                val batch = db.batch()
                BotPersona.defaultStarters.forEach { starter ->
                    val docRef = botsCol.document(starter.id)
                    batch.set(docRef, toCreateMap(starter, uid))
                }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    override suspend fun toggleBot(botId: String, currentActive: Boolean): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/bots/$botId"
        return try {
            val uid = requireUserId()
            require(VALID_ID_REGEX.matches(botId)) { "Invalid bot ID" }
            val docRef = db.collection("users").document(uid).collection("bots").document(botId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "active" to !currentActive,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    override suspend fun upsertBot(bot: BotPersona, isExisting: Boolean): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/bots/${bot.id}"
        return try {
            val uid = requireUserId()
            val cleanName = bot.name.trim().take(50)
            val cleanRole = bot.role.trim().take(80)
            val cleanPrompt = bot.systemPrompt.trim().take(1000)
            require(cleanName.isNotEmpty()) { "Bot name is required" }
            require(cleanRole.isNotEmpty()) { "Bot role is required" }
            require(cleanPrompt.isNotEmpty()) { "System prompt is required" }

            val sanitized = bot.copy(
                name = cleanName,
                role = cleanRole,
                opacityPercent = bot.opacityPercent.coerceIn(10, 100),
                inferenceMode = MoteInferenceMode.fromString(bot.inferenceMode).name,
                systemPrompt = cleanPrompt
            )

            val botsCol = db.collection("users").document(uid).collection("bots")
            val docRef = if (sanitized.id.isNotBlank() && VALID_ID_REGEX.matches(sanitized.id)) {
                botsCol.document(sanitized.id)
            } else {
                botsCol.document()
            }
            docPath = docRef.path

            if (isExisting) {
                docRef.update(toUpdateMap(sanitized)).await()
            } else {
                docRef.set(toCreateMap(sanitized, uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (isExisting) OperationType.UPDATE else OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    private fun toCreateMap(bot: BotPersona, uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "name" to bot.name.trim().take(50),
        "role" to bot.role.trim().take(80),
        "colorHex" to bot.colorHex,
        "active" to bot.active,
        "opacityPercent" to bot.opacityPercent.coerceIn(10, 100),
        "inferenceMode" to MoteInferenceMode.fromString(bot.inferenceMode).name,
        "systemPrompt" to bot.systemPrompt.trim().take(1000),
        "allowScreenRead" to bot.allowScreenRead,
        "allowActions" to bot.allowActions,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private fun toUpdateMap(bot: BotPersona): Map<String, Any> = mapOf(
        "name" to bot.name.trim().take(50),
        "role" to bot.role.trim().take(80),
        "colorHex" to bot.colorHex,
        "active" to bot.active,
        "opacityPercent" to bot.opacityPercent.coerceIn(10, 100),
        "inferenceMode" to MoteInferenceMode.fromString(bot.inferenceMode).name,
        "systemPrompt" to bot.systemPrompt.trim().take(1000),
        "allowScreenRead" to bot.allowScreenRead,
        "allowActions" to bot.allowActions,
        "updatedAt" to FieldValue.serverTimestamp()
    )

    companion object {
        private val VALID_ID_REGEX = Regex("^[a-zA-Z0-9_\\-]{1,128}$")
    }
}
