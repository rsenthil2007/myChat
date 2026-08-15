package io.microbear.mychat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.microbear.mychat.data.ChatMessage

private val Slate = Color(0xFF0F172A)
private val Panel = Color(0xFF1E293B)
private val Teal = Color(0xFF2DD4BF)
private val Mist = Color(0xFFE2E8F0)
private val Mute = Color(0xFF94A3B8)
private val Danger = Color(0xFFF87171)

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startRecording() else vm.onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Slate,
                    surface = Panel,
                    primary = Teal,
                    onPrimary = Slate,
                    onBackground = Mist,
                    onSurface = Mist,
                ),
            ) {
                val state = vm.ui
                when {
                    !state.joined -> JoinScreen(state, vm)
                    state.sketching -> SketchScreen(
                        busy = state.busy,
                        onCancel = vm::closeSketch,
                        onSend = vm::sendSketch,
                    )
                    else -> ChatScreen(
                        state = state,
                        vm = vm,
                        onMic = { onMicTap(state) },
                    )
                }
                if (state.confirmClear) {
                    AlertDialog(
                        onDismissRequest = vm::dismissClear,
                        title = { Text("Clear chat") },
                        text = {
                            Text("Clear all messages in room \"${state.roomId}\" for everyone? This cannot be undone.")
                        },
                        confirmButton = {
                            TextButton(onClick = vm::clearRoom) { Text("Clear", color = Danger) }
                        },
                        dismissButton = {
                            TextButton(onClick = vm::dismissClear) { Text("Cancel", color = Mute) }
                        },
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun onMicTap(state: ChatUiState) {
        if (state.recording) {
            vm.stopRecording(send = true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
private fun JoinScreen(state: ChatUiState, vm: ChatViewModel) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Teal,
        unfocusedBorderColor = Mute,
        focusedLabelColor = Teal,
        cursorColor = Teal,
        focusedTextColor = Mist,
        unfocusedTextColor = Mist,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate)
            .imePadding()
            .displayCutoutPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("myChat ${BuildConfig.VERSION_NAME}", color = Teal, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Join a room", color = Mist, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sealed text, sketches, and voice notes. Same rooms as the web app.",
            color = Mute,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = state.displayName,
            onValueChange = vm::onName,
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.roomInput,
            onValueChange = vm::onRoom,
            label = { Text("Room code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = vm::onServer,
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = vm::join,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Slate),
        ) {
            Text(if (state.busy) "Connecting…" else "Enter room")
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Danger, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ChatScreen(state: ChatUiState, vm: ChatViewModel, onMic: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate)
            .imePadding()
            .displayCutoutPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("myChat ${BuildConfig.VERSION_NAME}", color = Teal, fontSize = 12.sp)
                Text(state.roomId, color = Mist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(state.status, color = Mute, fontSize = 12.sp)
            }
            IconButton(onClick = vm::askClear, enabled = !state.busy) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear chat", tint = Mute)
            }
            TextButton(onClick = vm::leave) { Text("Leave", color = Teal) }
        }
        state.error?.let {
            Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if (state.recording) {
            Text(
                "Recording… tap the mic to send (max 60s)",
                color = Danger,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(msg, mine = msg.authorId == state.authorId, playing = state.playingId == msg.id) {
                    vm.toggleAudio(msg)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::openSketch, enabled = !state.busy && !state.recording) {
                Icon(Icons.Filled.Brush, contentDescription = "Sketch", tint = Teal)
            }
            IconButton(onClick = onMic, enabled = !state.busy) {
                Icon(
                    if (state.recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (state.recording) "Send voice note" else "Record voice note",
                    tint = if (state.recording) Danger else Teal,
                )
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = vm::onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Teal,
                    cursorColor = Teal,
                    focusedTextColor = Mist,
                    unfocusedTextColor = Mist,
                ),
            )
            Button(
                onClick = vm::send,
                enabled = !state.busy && state.draft.isNotBlank() && !state.recording,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Slate),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    mine: Boolean,
    playing: Boolean,
    onPlayAudio: () -> Unit,
) {
    val png = msg.sketchPng
    val audio = msg.audioBytes
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(msg.authorName, color = Mute, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        when {
            png != null -> {
                val bmp = remember(png) { DrawingRaster.decodeBitmap(png) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Sketch from ${msg.authorName}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .padding(4.dp),
                    )
                } else {
                    BubbleText(msg.displayText.ifBlank { "(empty drawing)" }, mine)
                }
            }
            audio != null -> {
                Button(
                    onClick = onPlayAudio,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (mine) Teal else Panel,
                        contentColor = if (mine) Slate else Mist,
                    ),
                ) {
                    Text(if (playing) "Stop voice note" else "Play voice note")
                }
            }
            else -> BubbleText(msg.displayText.ifBlank { "[empty]" }, mine)
        }
    }
}

@Composable
private fun BubbleText(body: String, mine: Boolean) {
    Text(
        body,
        color = if (mine) Slate else Mist,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .background(if (mine) Teal else Panel, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
