package io.microbear.mychat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.JsonObject
import io.microbear.mychat.data.AccessRequest
import io.microbear.mychat.data.ChatApi
import io.microbear.mychat.data.ChatMessage
import io.microbear.mychat.data.SecurePipe
import io.microbear.mychat.data.WhiteboardState
import io.microbear.mychat.data.toEnvelope
import io.microbear.mychat.data.toOutgoing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

const val DEFAULT_SERVER = "https://chat.microbear.in"

data class SketchStroke(
    val color: String,
    val size: Float,
    val points: List<Float>,
)

data class ChatUiState(
    val displayName: String = "",
    val roomInput: String = "lobby",
    val serverUrl: String = DEFAULT_SERVER,
    val authPhase: String = "checking",
    val mobileInput: String = "",
    val otp: String = "",
    val fatalError: String? = null,
    val authorId: String = "",
    val joined: Boolean = false,
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val status: String = "Not connected",
    val busy: Boolean = false,
    val error: String? = null,
    val sketching: Boolean = false,
    val recording: Boolean = false,
    val confirmClear: Boolean = false,
    val playingId: String? = null,
    val roomTab: String = "chat",
    val board: WhiteboardState = WhiteboardState(),
    val boardJoined: Boolean = false,
    val boardColor: String = "#0f172a",
    val boardPenSize: Float = 12f,
    val boardTool: String = "pen",
    val boardMineStrokes: List<BoardStroke> = emptyList(),
    val boardDragging: Boolean = false,
    val confirmClearBoard: Boolean = false,
    val isAdmin: Boolean = false,
    val showAdmin: Boolean = false,
    val accessRequests: List<AccessRequest> = emptyList(),
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mychat", Context.MODE_PRIVATE)
    private val api = ChatApi()
    private var pollJob: Job? = null
    private var boardSyncJob: Job? = null
    private var pendingJob: Job? = null
    private var adminToken: String = ""
    private var lastBoardStamp = ""
    private val openedCache = java.util.concurrent.ConcurrentHashMap<String, ChatMessage>()
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordTimeout: Job? = null
    private val player: ExoPlayer = ExoPlayer.Builder(app).build().also { exo ->
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    ui = ui.copy(playingId = null)
                }
            }
        })
    }

    var ui by mutableStateOf(
        ChatUiState(
            displayName = prefs.getString("name", "") ?: "",
            roomInput = prefs.getString("room", "lobby") ?: "lobby",
            serverUrl = DEFAULT_SERVER,
            mobileInput = prefs.getString("mobile", "") ?: "",
            authPhase = if (prefs.getString("mobile", "").isNullOrBlank()) "register" else "checking",
            authorId = prefs.getString("authorId", null) ?: newAuthorId().also {
                prefs.edit().putString("authorId", it).apply()
            },
        ),
    )
        private set

    private val phoneAuth = PhoneAuthHelper(
        onCodeSent = {
            ui = ui.copy(authPhase = "sms", busy = false, error = null)
        },
        onIdToken = { token -> completeSmsAuth(token) },
        onError = { msg ->
            ui = ui.copy(busy = false, error = msg)
        },
    )

    init {
        bootstrapDevice()
    }

    private fun deviceSsaid(): String {
        return Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: ""
    }

    fun onName(value: String) { ui = ui.copy(displayName = value.take(24), error = null) }
    fun onRoom(value: String) { ui = ui.copy(roomInput = value.take(24), error = null) }
    fun onMobile(value: String) {
        ui = ui.copy(mobileInput = value.filter { it.isDigit() || it == '+' }.take(16), error = null)
    }
    fun onOtp(value: String) { ui = ui.copy(otp = value.filter { it.isDigit() }.take(8), error = null) }
    fun onDraft(value: String) { ui = ui.copy(draft = value.take(2000)) }

    fun bootstrapDevice() {
        val mobile = prefs.getString("mobile", "") ?: ""
        if (mobile.isBlank()) {
            ui = ui.copy(authPhase = "register", busy = false)
            return
        }
        ui = ui.copy(authPhase = "checking", busy = true, error = null, fatalError = null)
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    api.checkDevice(DEFAULT_SERVER, mobile, deviceSsaid())
                }
                applyAccount(res, mobile)
            } catch (e: Exception) {
                applyDeviceError(e.message ?: "Could not verify this device")
            }
        }
    }

    fun requestAccess() {
        val name = ui.displayName.trim()
        if (name.length < 2) {
            ui = ui.copy(error = "Enter a user name (at least 2 characters).")
            return
        }
        val mobile = digitsMobile(ui.mobileInput)
        if (mobile.length < 10) {
            ui = ui.copy(error = "Enter a valid mobile number.")
            return
        }
        ui = ui.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    api.requestAccess(DEFAULT_SERVER, mobile, deviceSsaid(), name)
                }
                applyAccount(res, res.mobile ?: mobile)
            } catch (e: Exception) {
                applyDeviceError(e.message ?: "Could not send this request")
            }
        }
    }

    private fun digitsMobile(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.startsWith("91") && digits.length == 12) digits.drop(2) else digits
    }

    private fun applyAccount(res: io.microbear.mychat.data.DeviceAuthResponse, mobileHint: String) {
        val name = res.displayName?.takeIf { it.isNotBlank() } ?: ui.displayName
        val mobile = digitsMobile(res.mobile ?: mobileHint)
        if (mobile.isNotBlank()) prefs.edit().putString("mobile", mobile).apply()
        if (name.isNotBlank()) prefs.edit().putString("name", name).apply()
        val status = res.status.orEmpty().ifBlank { if (res.ok) "admitted" else "pending" }
        when (status) {
            "admitted" -> {
                pendingJob?.cancel()
                pendingJob = null
                ui = ui.copy(
                    authPhase = "ok",
                    busy = false,
                    error = null,
                    displayName = name,
                    mobileInput = mobile,
                    isAdmin = res.isAdmin,
                    showAdmin = false,
                )
            }
            "pending" -> {
                ui = ui.copy(
                    authPhase = "pending",
                    busy = false,
                    error = null,
                    displayName = name,
                    mobileInput = mobile,
                    isAdmin = false,
                )
                startPendingPoll()
            }
            "rejected" -> {
                pendingJob?.cancel()
                ui = ui.copy(
                    authPhase = "blocked",
                    busy = false,
                    fatalError = "This registration was declined.",
                    error = "This registration was declined.",
                )
            }
            else -> ui = ui.copy(busy = false, error = "Unexpected account status")
        }
    }

    private fun startPendingPoll() {
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            while (isActive && ui.authPhase == "pending") {
                delay(4000)
                val mobile = prefs.getString("mobile", "") ?: return@launch
                try {
                    val res = withContext(Dispatchers.IO) {
                        api.checkDevice(DEFAULT_SERVER, mobile, deviceSsaid())
                    }
                    applyAccount(res, mobile)
                } catch (_: Exception) {
                    // Keep waiting; the waiting screen already explains the delay.
                }
            }
        }
    }

    fun showAdminDesk() {
        if (!ui.isAdmin) return
        ui = ui.copy(showAdmin = true, busy = true, error = null)
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    api.adminSession(DEFAULT_SERVER, digitsMobile(ui.mobileInput), deviceSsaid()).token
                } ?: error("Could not open admin")
                adminToken = token
                val list = withContext(Dispatchers.IO) { api.adminRequests(DEFAULT_SERVER, token) }
                ui = ui.copy(busy = false, accessRequests = list.requests, error = null)
            } catch (e: Exception) {
                ui = ui.copy(busy = false, error = e.message ?: "Could not load requests")
            }
        }
    }

    fun hideAdminDesk() {
        ui = ui.copy(showAdmin = false, accessRequests = emptyList())
    }

    fun refreshAdminRequests() {
        if (adminToken.isBlank()) {
            showAdminDesk()
            return
        }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { api.adminRequests(DEFAULT_SERVER, adminToken) }
                ui = ui.copy(accessRequests = list.requests, error = null)
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "Could not load requests")
            }
        }
    }

    fun admitRequest(mobile: String) = decideRequest(mobile, admit = true)
    fun rejectRequest(mobile: String) = decideRequest(mobile, admit = false)

    private fun decideRequest(mobile: String, admit: Boolean) {
        if (adminToken.isBlank()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (admit) api.adminAdmit(DEFAULT_SERVER, adminToken, mobile)
                    else api.adminReject(DEFAULT_SERVER, adminToken, mobile)
                }
                refreshAdminRequests()
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "Could not update that request")
            }
        }
    }

    fun sendSms(activity: Activity) {
        val name = ui.displayName.trim()
        if (name.length < 2) {
            ui = ui.copy(error = "Enter a user name (at least 2 characters).")
            return
        }
        val mobile = ui.mobileInput.trim()
        if (mobile.filter { it.isDigit() }.length < 10) {
            ui = ui.copy(error = "Enter a valid mobile number.")
            return
        }
        ui = ui.copy(busy = true, error = null)
        phoneAuth.sendSms(activity, toE164(mobile))
    }

    fun confirmSms() {
        ui = ui.copy(busy = true, error = null)
        phoneAuth.confirmSms(ui.otp)
    }

    private fun completeSmsAuth(idToken: String) {
        viewModelScope.launch {
            ui = ui.copy(busy = true, error = null)
            try {
                val name = ui.displayName.trim()
                val res = withContext(Dispatchers.IO) {
                    api.registerDevice(DEFAULT_SERVER, idToken, deviceSsaid(), name)
                }
                val storedName = res.displayName?.takeIf { it.isNotBlank() } ?: name
                val mobile = (res.mobile ?: ui.mobileInput).filter { it.isDigit() }.let { digits ->
                    if (digits.startsWith("91") && digits.length == 12) digits.drop(2) else digits
                }
                prefs.edit()
                    .putString("mobile", mobile)
                    .putString("name", storedName)
                    .apply()
                ui = ui.copy(
                    authPhase = "ok",
                    busy = false,
                    error = null,
                    otp = "",
                    displayName = storedName,
                    mobileInput = mobile,
                )
            } catch (e: Exception) {
                applyDeviceError(e.message ?: "Could not register this phone")
            }
        }
    }

    private fun applyDeviceError(msg: String) {
        val mismatch = msg.contains("does not match", ignoreCase = true) ||
            msg.contains("already registered", ignoreCase = true) ||
            msg.contains("declined", ignoreCase = true)
        if (mismatch) {
            ui = ui.copy(authPhase = "blocked", busy = false, fatalError = msg, error = msg)
        } else {
            ui = ui.copy(busy = false, error = msg)
        }
    }

    fun join() {
        val name = ui.displayName.trim()
        if (name.length < 2) {
            ui = ui.copy(error = "Your user name is missing. Close the app and register again.")
            return
        }
        val room = RoomIds.normalize(ui.roomInput)
        val server = DEFAULT_SERVER
        prefs.edit()
            .putString("name", name)
            .putString("room", room)
            .apply()

        ui = ui.copy(busy = true, error = null, status = "Connecting…")
        viewModelScope.launch {
            try {
                val health = withContext(Dispatchers.IO) { api.health(server) }
                if (!health.ok) error("Server is not ready")
                val (opened, count, board) = withContext(Dispatchers.IO) {
                    val snap = api.loadRoom(server, room)
                    Triple(reveal(snap.messages, room), snap.messages.size, snap.whiteboard)
                }
                ui = ui.copy(
                    busy = false,
                    joined = true,
                    displayName = name,
                    roomId = room,
                    serverUrl = server,
                    messages = opened,
                    status = "Synced · $count messages",
                    roomTab = "chat",
                )
                applyWhiteboard(board, force = true)
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
        stopPlayback()
        cancelRecording()
        pollJob?.cancel()
        pollJob = null
        boardSyncJob?.cancel()
        boardSyncJob = null
        lastBoardStamp = ""
        openedCache.clear()
        SecurePipe.clearKeyCache()
        ui = ui.copy(
            joined = false,
            messages = emptyList(),
            draft = "",
            status = "Left room",
            sketching = false,
            recording = false,
            confirmClear = false,
            roomTab = "chat",
            board = WhiteboardState(),
            boardJoined = false,
            boardMineStrokes = emptyList(),
            boardDragging = false,
            confirmClearBoard = false,
        )
    }

    fun openSketch() { ui = ui.copy(sketching = true, error = null) }
    fun closeSketch() { ui = ui.copy(sketching = false) }
    fun askClear() { ui = ui.copy(confirmClear = true) }
    fun dismissClear() { ui = ui.copy(confirmClear = false) }
    fun showChatTab() { ui = ui.copy(roomTab = "chat") }
    fun showBoardTab() {
        ui = ui.copy(roomTab = "board", error = null)
        if (!ui.boardJoined) joinBoard()
    }
    fun onBoardPenSize(value: Float) { ui = ui.copy(boardPenSize = value) }
    fun onBoardTool(value: String) { ui = ui.copy(boardTool = value) }
    fun setBoardDragging(value: Boolean) { ui = ui.copy(boardDragging = value) }
    fun askClearBoard() { ui = ui.copy(confirmClearBoard = true) }
    fun dismissClearBoard() { ui = ui.copy(confirmClearBoard = false) }

    fun joinBoard() {
        if (!ui.joined) return
        val server = ui.serverUrl
        val roomId = ui.roomId
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    api.whiteboardAction(server, roomId, "join", authorBody())
                }
                ui = ui.copy(boardJoined = true)
                applyWhiteboard(res.whiteboard, force = true)
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "Could not join the board")
            }
        }
    }

    fun addBoardStroke(stroke: BoardStroke) {
        ui = ui.copy(boardMineStrokes = ui.boardMineStrokes + stroke, boardDragging = false)
        queueBoardSync()
    }

    fun undoBoard() {
        if (ui.boardMineStrokes.isEmpty()) return
        ui = ui.copy(boardMineStrokes = ui.boardMineStrokes.dropLast(1))
        val server = ui.serverUrl
        val roomId = ui.roomId
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    api.whiteboardAction(server, roomId, "undo", authorBody())
                }
                ui = ui.copy(boardJoined = true)
                applyWhiteboard(res.whiteboard, force = true)
            } catch (_: Exception) {
                queueBoardSync(immediate = true)
            }
        }
    }

    fun clearBoardMine() {
        ui = ui.copy(boardMineStrokes = emptyList())
        val server = ui.serverUrl
        val roomId = ui.roomId
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    api.whiteboardAction(server, roomId, "clear-mine", authorBody())
                }
                applyWhiteboard(res.whiteboard, force = true)
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "Could not clear your layer")
            }
        }
    }

    fun clearBoardAll() {
        ui = ui.copy(confirmClearBoard = false, boardMineStrokes = emptyList(), boardJoined = false)
        val server = ui.serverUrl
        val roomId = ui.roomId
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    api.whiteboardAction(server, roomId, "clear-all", authorBody())
                }
                applyWhiteboard(res.whiteboard, force = true)
                joinBoard()
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "Could not clear the board")
            }
        }
    }

    private fun authorBody(): Map<String, Any?> = mapOf(
        "authorId" to ui.authorId,
        "authorName" to ui.displayName,
    )

    private fun queueBoardSync(immediate: Boolean = false) {
        boardSyncJob?.cancel()
        boardSyncJob = viewModelScope.launch {
            if (!immediate) delay(220)
            val (w, h) = boardLogicalSize(ui.board)
            val server = ui.serverUrl
            val roomId = ui.roomId
            val drawing = mapOf(
                "w" to w,
                "h" to h,
                "strokes" to ui.boardMineStrokes.map { it.toPayload() },
            )
            val body = authorBody().toMutableMap()
            body["drawing"] = drawing
            try {
                val res = withContext(Dispatchers.IO) {
                    api.whiteboardAction(server, roomId, "stroke", body)
                }
                ui = ui.copy(boardJoined = true)
                applyWhiteboard(res.whiteboard, force = true)
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "Could not sync the board")
            }
        }
    }

    private fun applyWhiteboard(board: WhiteboardState?, force: Boolean = false) {
        if (board == null) return
        val stamp = board.updatedAt + ":" + board.layers.joinToString("|") {
            "${it.authorId}:${it.strokes.size}:${it.assignedColor}:${it.updatedAt}"
        }
        if (!force && stamp == lastBoardStamp) return
        if (ui.boardDragging && !force) return
        lastBoardStamp = stamp
        val mine = board.layers.find { it.authorId == ui.authorId }
        val nextMine = if (force && !ui.boardDragging) {
            mine?.strokes?.map { it.toStroke() } ?: emptyList()
        } else {
            ui.boardMineStrokes
        }
        ui = ui.copy(
            board = board,
            boardJoined = ui.boardJoined || mine != null,
            boardColor = mine?.assignedColor?.ifBlank { ui.boardColor } ?: ui.boardColor,
            boardMineStrokes = nextMine,
        )
    }

    fun onMicDenied() {
        ui = ui.copy(error = "Microphone permission is required for voice notes.")
    }

    fun send() {
        val text = ui.draft.trim()
        if (!ui.joined || text.isEmpty() || ui.busy) return
        ui = ui.copy(draft = "")
        postSecure("text", restoreDraft = text) { SecurePipe.sealText(text, ui.roomId) }
    }

    fun sendSketch(width: Int, height: Int, strokes: List<SketchStroke>) {
        if (!ui.joined || ui.busy || strokes.isEmpty()) return
        val payload = mapOf(
            "w" to width,
            "h" to height,
            "strokes" to strokes.map {
                mapOf("t" to "pen", "c" to it.color, "s" to it.size, "p" to it.points)
            },
        )
        ui = ui.copy(sketching = false)
        postSecure("drawing") { SecurePipe.sealJson(payload, ui.roomId) }
    }

    fun startRecording() {
        if (!ui.joined || ui.busy || ui.recording) return
        try {
            val file = File(getApplication<Application>().cacheDir, "mychat-rec.m4a")
            if (file.exists()) file.delete()
            val rec = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(getApplication())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = file
            ui = ui.copy(recording = true, error = null)
            recordTimeout?.cancel()
            recordTimeout = viewModelScope.launch {
                delay(60_000)
                if (ui.recording) stopRecording(send = true)
            }
        } catch (e: Exception) {
            cancelRecording()
            ui = ui.copy(error = e.message ?: "Could not start microphone")
        }
    }

    fun stopRecording(send: Boolean) {
        recordTimeout?.cancel()
        recordTimeout = null
        val file = recordFile
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        recorder = null
        recordFile = null
        ui = ui.copy(recording = false)
        if (!send || file == null || !file.exists() || file.length() < 64) {
            file?.delete()
            if (send) ui = ui.copy(error = "Recording was empty — tap the mic to start, tap again to send.")
            return
        }
        val bytes = file.readBytes()
        file.delete()
        val b64 = Base64.getEncoder().encodeToString(bytes)
        postSecure("audio") {
            SecurePipe.sealJson(mapOf("mime" to "audio/mp4", "audio" to b64), ui.roomId)
        }
    }

    fun toggleAudio(msg: ChatMessage) {
        if (ui.playingId == msg.id) {
            stopPlayback()
            return
        }
        val bytes = msg.audioBytes ?: return
        val mime = msg.audioMime ?: "audio/webm"
        val ext = when {
            "mp4" in mime || "aac" in mime || "m4a" in mime -> "m4a"
            "webm" in mime -> "webm"
            "ogg" in mime -> "ogg"
            else -> "bin"
        }
        try {
            val file = File(getApplication<Application>().cacheDir, "mychat-${msg.id}.$ext")
            file.writeBytes(bytes)
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
            ui = ui.copy(playingId = msg.id, error = null)
        } catch (e: Exception) {
            ui = ui.copy(error = "Could not play this voice note on Android.")
        }
    }

    fun clearRoom() {
        if (!ui.joined || ui.busy) return
        ui = ui.copy(busy = true, confirmClear = false, error = null)
        val roomId = ui.roomId
        val server = ui.serverUrl
        viewModelScope.launch {
            try {
                val (opened, count) = withContext(Dispatchers.IO) {
                    val snap = api.clearRoom(server, roomId)
                    openedCache.clear()
                    reveal(snap.messages, roomId) to snap.messages.size
                }
                ui = ui.copy(busy = false, messages = opened, status = "Synced · $count messages")
            } catch (e: Exception) {
                ui = ui.copy(busy = false, error = e.message ?: "Could not clear the room")
            }
        }
    }

    private fun postSecure(type: String, restoreDraft: String? = null, build: () -> SecurePipe.Envelope) {
        val roomId = ui.roomId
        val server = ui.serverUrl
        val authorId = ui.authorId
        val authorName = ui.displayName
        val createdAt = Instant.now().toString()
        ui = ui.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val (opened, count) = withContext(Dispatchers.IO) {
                    val outgoing = build().toOutgoing(type, authorId, authorName, createdAt)
                    val snap = api.sendSecure(server, roomId, outgoing)
                    reveal(snap.messages, roomId) to snap.messages.size
                }
                ui = ui.copy(busy = false, messages = opened, status = "Synced · $count messages")
            } catch (e: Exception) {
                ui = ui.copy(
                    busy = false,
                    draft = restoreDraft ?: ui.draft,
                    error = e.message ?: "Send failed",
                )
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && ui.joined) {
                delay(2000)
                try {
                    val (opened, count, board) = withContext(Dispatchers.IO) {
                        val snap = api.loadRoom(ui.serverUrl, ui.roomId)
                        Triple(reveal(snap.messages, ui.roomId), snap.messages.size, snap.whiteboard)
                    }
                    ui = ui.copy(
                        messages = opened,
                        status = "Synced · $count messages",
                        error = null,
                    )
                    applyWhiteboard(board)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (ui.status.startsWith("Synced")) {
                        ui = ui.copy(status = "Reconnecting…")
                    } else {
                        ui = ui.copy(status = "Reconnecting…", error = e.message)
                    }
                }
            }
        }
    }

    private fun reveal(messages: List<ChatMessage>, roomId: String): List<ChatMessage> {
        return messages.map { msg ->
            openedCache[msg.id]?.let { cached ->
                return@map msg.copy(
                    displayText = cached.displayText,
                    sketchPng = cached.sketchPng,
                    audioBytes = cached.audioBytes,
                    audioMime = cached.audioMime,
                    audioDurationMs = cached.audioDurationMs,
                )
            }
            val opened = openMessage(msg, roomId)
            if (!opened.displayText.startsWith("Could not decrypt")) {
                openedCache[msg.id] = opened
            }
            opened
        }
    }

    private fun openMessage(msg: ChatMessage, roomId: String): ChatMessage {
        when (msg.type) {
            "drawing" -> {
                val body = openBody(msg, roomId) ?: return msg.copy(displayText = "Could not decrypt — check the room code.")
                val png = DrawingRaster.pngFromBody(body)
                return msg.copy(
                    displayText = if (png == null) "(empty drawing)" else "",
                    sketchPng = png,
                )
            }
            "audio" -> {
                val body = openBody(msg, roomId) ?: return msg.copy(displayText = "Could not decrypt — check the room code.")
                val b64 = body.get("audio")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val mime = body.get("mime")?.takeIf { it.isJsonPrimitive }?.asString ?: "audio/webm"
                val bytes = try {
                    if (b64.isBlank()) null else Base64.getDecoder().decode(b64)
                } catch (_: Exception) {
                    null
                }
                val durationMs = if (bytes != null) audioDurationMs(bytes, mime) else 0L
                return msg.copy(
                    displayText = if (bytes == null) "(invalid audio)" else formatVoiceLabel(durationMs),
                    audioBytes = bytes,
                    audioMime = mime,
                    audioDurationMs = durationMs,
                )
            }
            else -> {
                if (!msg.secure) {
                    return msg.copy(displayText = msg.text.orEmpty().ifBlank { "[empty]" })
                }
                val envelope = msg.toEnvelope() ?: return msg.copy(displayText = "Could not decrypt — check the room code.")
                return try {
                    msg.copy(displayText = SecurePipe.openText(envelope, roomId).ifBlank { "[empty]" })
                } catch (_: Exception) {
                    msg.copy(displayText = "Could not decrypt — check the room code.")
                }
            }
        }
    }

    private fun openBody(msg: ChatMessage, roomId: String): JsonObject? {
        if (!msg.secure) {
            if (!msg.imageData.isNullOrBlank()) {
                val obj = JsonObject()
                obj.addProperty("imageData", msg.imageData)
                return obj
            }
            return null
        }
        val envelope = msg.toEnvelope() ?: return null
        return try {
            SecurePipe.open(envelope, roomId)
        } catch (_: Exception) {
            null
        }
    }

    private fun stopPlayback() {
        try {
            player.stop()
            player.clearMediaItems()
        } catch (_: Exception) {
        }
        ui = ui.copy(playingId = null)
    }

    private fun cancelRecording() {
        recordTimeout?.cancel()
        recordTimeout = null
        try {
            recorder?.apply {
                reset()
                release()
            }
        } catch (_: Exception) {
        }
        recorder = null
        recordFile?.delete()
        recordFile = null
    }

    private fun audioDurationMs(bytes: ByteArray, mime: String): Long {
        val ext = when {
            "mp4" in mime || "aac" in mime || "m4a" in mime -> "m4a"
            "webm" in mime -> "webm"
            "ogg" in mime -> "ogg"
            else -> "bin"
        }
        val file = File(getApplication<Application>().cacheDir, "mychat-dur-$ext")
        val retriever = MediaMetadataRetriever()
        return try {
            file.writeBytes(bytes)
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            file.delete()
        }
    }

    private fun formatVoiceLabel(durationMs: Long): String {
        if (durationMs <= 0L) return "Voice note"
        val totalSec = (durationMs + 500) / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "Voice note · ${min}m ${sec}s" else "Voice note · ${sec}s"
    }

    private fun newAuthorId(): String =
        "a_" + UUID.randomUUID().toString().replace("-", "").take(12)

    override fun onCleared() {
        pendingJob?.cancel()
        stopPlayback()
        cancelRecording()
        player.release()
        super.onCleared()
    }
}
