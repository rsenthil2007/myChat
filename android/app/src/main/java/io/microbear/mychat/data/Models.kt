package io.microbear.mychat.data

import com.google.gson.annotations.SerializedName
import kotlin.jvm.Transient

data class DeviceAuthResponse(
    val ok: Boolean = false,
    val otp: String? = null,
    val mobile: String? = null,
    val displayName: String? = null,
    val status: String? = null,
    val isAdmin: Boolean = false,
    val token: String? = null,
    val error: String? = null,
)

data class AccessRequest(
    val mobile: String = "",
    val displayName: String = "",
    val status: String = "",
    val isAdmin: Boolean = false,
    val createdAt: String = "",
    val ssaidTail: String = "",
)

data class AdminListResponse(
    val ok: Boolean = false,
    val requests: List<AccessRequest> = emptyList(),
    val error: String? = null,
)

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
    val whiteboard: WhiteboardState? = null,
)

data class WhiteboardState(
    val w: Int = 0,
    val h: Int = 0,
    val layers: List<BoardLayer> = emptyList(),
    val palette: List<BoardSwatch> = emptyList(),
    val updatedAt: String = "",
)

data class BoardLayer(
    val authorId: String = "",
    val authorName: String = "",
    val assignedColor: String = "",
    val extraColors: List<String> = emptyList(),
    val strokes: List<BoardStrokeDto> = emptyList(),
    val updatedAt: String = "",
)

data class BoardStrokeDto(
    val t: String = "pen",
    val c: String = "#0f172a",
    val s: Float = 4f,
    val p: List<Float> = emptyList(),
    val tx: String? = null,
)

data class BoardSwatch(
    val id: String = "",
    val name: String = "",
    val hex: String = "",
)

data class WhiteboardActionResponse(
    val room: RoomSnapshot? = null,
    val whiteboard: WhiteboardState? = null,
    val error: String? = null,
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
    @field:Transient val audioDurationMs: Long = 0L,
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
