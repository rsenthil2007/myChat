package io.microbear.mychat.data

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    val ok: Boolean = false,
    val version: Int = 0,
    val sync: String? = null,
)

data class RoomSnapshot(
    val version: Int = 1,
    val roomId: String = "",
    val updatedAt: String = "",
    val messages: List<ChatMessage> = emptyList(),
)

data class ChatMessage(
    val id: String = "",
    val type: String = "text",
    val authorId: String = "",
    val authorName: String = "",
    val createdAt: String = "",
    val text: String? = null,
    val secure: Boolean = false,
    @SerializedName("imageData") val imageData: String? = null,
)

data class OutgoingText(
    val id: String,
    val type: String = "text",
    val authorId: String,
    val authorName: String,
    val createdAt: String,
    val text: String,
)
