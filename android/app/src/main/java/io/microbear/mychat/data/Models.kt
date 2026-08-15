package io.microbear.mychat.data

import com.google.gson.annotations.SerializedName
import kotlin.jvm.Transient

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
    val v: Int = 0,
    val zip: Int = 0,
    val iv: String? = null,
    val mac: String? = null,
    val data: String? = null,
    @SerializedName("imageData") val imageData: String? = null,
    @field:Transient val displayText: String = "",
    @field:Transient val sketchPng: ByteArray? = null,
    @field:Transient val audioBytes: ByteArray? = null,
    @field:Transient val audioMime: String? = null,
)

data class OutgoingSecure(
    val id: String,
    val type: String,
    val authorId: String,
    val authorName: String,
    val createdAt: String,
    val secure: Boolean = true,
    val v: Int,
    val zip: Int,
    val iv: String,
    val mac: String,
    val data: String,
)

fun SecurePipe.Envelope.toOutgoing(
    type: String,
    authorId: String,
    authorName: String,
    createdAt: String,
    id: String = "m_${System.currentTimeMillis().toString(36)}_${java.util.UUID.randomUUID().toString().take(6)}",
): OutgoingSecure = OutgoingSecure(
    id = id,
    type = type,
    authorId = authorId,
    authorName = authorName,
    createdAt = createdAt,
    v = v,
    zip = zip,
    iv = iv,
    mac = mac,
    data = data,
)
