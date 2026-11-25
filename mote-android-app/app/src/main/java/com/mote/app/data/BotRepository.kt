package com.mote.app.data

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.mote.app.model.BotPersona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local-first bot store synced with Cloud Firestore when signed in via Firebase Auth.
 */
class BotRepository {

    private val _bots = MutableStateFlow(BotPersona.defaultStarters)
    val bots: StateFlow<List<BotPersona>> = _bots.asStateFlow()

    fun toggleBot(botId: String) {
        _bots.value = _bots.value.map {
            if (it.id == botId) it.copy(active = !it.active) else it
        }
        syncBotToFirestore(_bots.value.firstOrNull { it.id == botId })
    }

    fun upsertBot(bot: BotPersona) {
        val existing = _bots.value.any { it.id == bot.id }
        _bots.value = if (existing) {
            _bots.value.map { if (it.id == bot.id) bot else it }
        } else {
            _bots.value + bot
        }
        syncBotToFirestore(bot)
    }

    private fun syncBotToFirestore(bot: BotPersona?) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        if (bot == null) return
        runCatching {
            Firebase.firestore
                .collection("users")
                .document(uid)
                .collection("bots")
                .document(bot.id)
                .set(
                    mapOf(
                        "name" to bot.name,
                        "role" to bot.role,
                        "colorHex" to bot.colorHex,
                        "active" to bot.active,
                        "opacityPercent" to bot.opacityPercent,
                        "inferenceMode" to bot.inferenceMode,
                        "systemPrompt" to bot.systemPrompt,
                        "allowScreenRead" to bot.allowScreenRead
                    )
                )
        }
    }
}
