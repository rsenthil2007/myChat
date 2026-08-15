package io.microbear.mychat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.microbear.mychat.data.ChatApi
import io.microbear.mychat.data.ChatMessage
import io.microbear.mychat.data.OutgoingSecureText
import io.microbear.mychat.data.SecurePipe
import io.microbear.mychat.data.toEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

const val DEFAULT_SERVER = "http://10.0.2.2:8080"

data class ChatUiState(
    val displayName: String = "",
    val roomInput: String = "lobby",
    val serverUrl: String = DEFAULT_SERVER,
    val authorId: String = "",
    val joined: Boolean = false,
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val status: String = "Not connected",
    val busy: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mychat", Context.MODE_PRIVATE)
    private val api = ChatApi()
    private var pollJob: Job? = null
    private val openedCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    var ui by mutableStateOf(
        ChatUiState(
            displayName = prefs.getString("name", "") ?: "",
            roomInput = prefs.getString("room", "lobby") ?: "lobby",
            serverUrl = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER,
            authorId = prefs.getString("authorId", null) ?: newAuthorId().also {
                prefs.edit().putString("authorId", it).apply()
            },
        ),
    )
        private set

    fun onName(value: String) { ui = ui.copy(displayName = value.take(24), error = null) }
    fun onRoom(value: String) { ui = ui.copy(roomInput = value.take(24), error = null) }
    fun onServer(value: String) { ui = ui.copy(serverUrl = value, error = null) }
    fun onDraft(value: String) { ui = ui.copy(draft = value.take(2000)) }

    fun join() {
        val name = ui.displayName.trim().ifEmpty { "Guest" }
        val room = RoomIds.normalize(ui.roomInput)
        val server = ui.serverUrl.trim().trimEnd('/')
        if (server.isEmpty()) {
            ui = ui.copy(error = "Enter the chat server URL.")
            return
        }
        prefs.edit()
            .putString("name", name)
            .putString("room", room)
            .putString("server", server)
            .apply()

        ui = ui.copy(busy = true, error = null, status = "Connecting…")
        viewModelScope.launch {
            try {
                val health = withContext(Dispatchers.IO) { api.health(server) }
                if (!health.ok) error("Server is not ready")
                val (opened, count) = withContext(Dispatchers.IO) {
                    val snap = api.loadRoom(server, room)
                    reveal(snap.messages, room) to snap.messages.size
                }
                ui = ui.copy(
                    busy = false,
                    joined = true,
                    displayName = name,
                    roomId = room,
                    serverUrl = server,
                    messages = opened,
                    status = "Synced · $count messages",
                )
                startPolling()
            } catch (e: Exception) {
                ui = ui.copy(
                    busy = false,
                    joined = false,
                    error = e.message ?: "Could not join room",
                    status = "Offline",
                )
            }
        }
    }

    fun leave() {
        pollJob?.cancel()
        pollJob = null
        openedCache.clear()
        SecurePipe.clearKeyCache()
        ui = ui.copy(joined = false, messages = emptyList(), draft = "", status = "Left room")
    }

    fun send() {
        val text = ui.draft.trim()
        if (!ui.joined || text.isEmpty() || ui.busy) return
        val roomId = ui.roomId
        val server = ui.serverUrl
        val outgoingMeta = Triple(ui.authorId, ui.displayName, Instant.now().toString())
        ui = ui.copy(busy = true, draft = "", error = null)
        viewModelScope.launch {
            try {
                val (opened, count) = withContext(Dispatchers.IO) {
                    val envelope = SecurePipe.sealText(text, roomId)
                    val outgoing = OutgoingSecureText(
                        id = "m_${System.currentTimeMillis().toString(36)}_${UUID.randomUUID().toString().take(6)}",
                        authorId = outgoingMeta.first,
                        authorName = outgoingMeta.second,
                        createdAt = outgoingMeta.third,
                        v = envelope.v,
                        zip = envelope.zip,
                        iv = envelope.iv,
                        mac = envelope.mac,
                        data = envelope.data,
                    )
                    val snap = api.sendSecureText(server, roomId, outgoing)
                    reveal(snap.messages, roomId) to snap.messages.size
                }
                ui = ui.copy(
                    busy = false,
                    messages = opened,
                    status = "Synced · $count messages",
                )
            } catch (e: Exception) {
                ui = ui.copy(busy = false, draft = text, error = e.message ?: "Send failed")
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && ui.joined) {
                delay(2000)
                try {
                    val (opened, count) = withContext(Dispatchers.IO) {
                        val snap = api.loadRoom(ui.serverUrl, ui.roomId)
                        reveal(snap.messages, ui.roomId) to snap.messages.size
                    }
                    ui = ui.copy(
                        messages = opened,
                        status = "Synced · $count messages",
                        error = null,
                    )
                } catch (e: Exception) {
                    ui = ui.copy(status = "Reconnecting…", error = e.message)
                }
            }
        }
    }

    private fun reveal(messages: List<ChatMessage>, roomId: String): List<ChatMessage> {
        return messages.map { msg ->
            val cached = openedCache[msg.id]
            if (cached != null) return@map msg.copy(displayText = cached)
            val shown = when (msg.type) {
                "drawing" -> "[sketch]"
                "audio" -> "[voice note]"
                else -> openTextMessage(msg, roomId)
            }
            if (shown != "Could not decrypt — check the room code.") {
                openedCache[msg.id] = shown
            }
            msg.copy(displayText = shown)
        }
    }

    private fun openTextMessage(msg: ChatMessage, roomId: String): String {
        if (!msg.secure) return msg.text.orEmpty().ifBlank { "[empty]" }
        val envelope = msg.toEnvelope() ?: return "Could not decrypt — check the room code."
        return try {
            SecurePipe.openText(envelope, roomId).ifBlank { "[empty]" }
        } catch (_: Exception) {
            "Could not decrypt — check the room code."
        }
    }

    private fun newAuthorId(): String =
        "a_" + UUID.randomUUID().toString().replace("-", "").take(12)
}
