package `in`.microbear.mychat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.microbear.mychat.data.ChatApi
import `in`.microbear.mychat.data.ChatMessage
import `in`.microbear.mychat.data.OutgoingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

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
                val snap = withContext(Dispatchers.IO) { api.loadRoom(server, room) }
                ui = ui.copy(
                    busy = false,
                    joined = true,
                    displayName = name,
                    roomId = room,
                    serverUrl = server,
                    messages = snap.messages,
                    status = "Synced · ${snap.messages.size} messages",
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
        ui = ui.copy(joined = false, messages = emptyList(), draft = "", status = "Left room")
    }

    fun send() {
        val text = ui.draft.trim()
        if (!ui.joined || text.isEmpty() || ui.busy) return
        val outgoing = OutgoingText(
            id = "m_${System.currentTimeMillis().toString(36)}_${UUID.randomUUID().toString().take(6)}",
            authorId = ui.authorId,
            authorName = ui.displayName,
            createdAt = Instant.now().toString(),
            text = text,
        )
        ui = ui.copy(busy = true, draft = "", error = null)
        viewModelScope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    api.sendText(ui.serverUrl, ui.roomId, outgoing)
                }
                ui = ui.copy(
                    busy = false,
                    messages = snap.messages,
                    status = "Synced · ${snap.messages.size} messages",
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
                    val snap = withContext(Dispatchers.IO) { api.loadRoom(ui.serverUrl, ui.roomId) }
                    ui = ui.copy(
                        messages = snap.messages,
                        status = "Synced · ${snap.messages.size} messages",
                        error = null,
                    )
                } catch (e: Exception) {
                    ui = ui.copy(status = "Reconnecting…", error = e.message)
                }
            }
        }
    }

    private fun newAuthorId(): String =
        "a_" + UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        const val DEFAULT_SERVER = "http://10.0.2.2:8080"
    }
}
