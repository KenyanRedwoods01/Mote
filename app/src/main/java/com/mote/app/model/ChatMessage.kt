package com.mote.app.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class ChatRole {
    USER,
    ASSISTANT;

    companion object {
        fun fromString(raw: String?): ChatRole =
            entries.firstOrNull { it.name == raw } ?: USER
    }
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = ChatRole.USER.name,
    val botId: String = "focus",
    val botName: String = "Focus",
    val text: String = "",
    val engineUsed: String = "NONE",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun chatRole(): ChatRole = ChatRole.fromString(role)
}
