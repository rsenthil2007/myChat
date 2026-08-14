package `in`.microbear.mychat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.microbear.mychat.data.ChatMessage

private val Slate = Color(0xFF0F172A)
private val Panel = Color(0xFF1E293B)
private val Teal = Color(0xFF2DD4BF)
private val Mist = Color(0xFFE2E8F0)
private val Mute = Color(0xFF94A3B8)
private val Danger = Color(0xFFF87171)

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                if (state.joined) ChatScreen(state, vm) else JoinScreen(state, vm)
            }
        }
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
            .padding(24.dp)
            .imePadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("myChat", color = Teal, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Join a room", color = Mist, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "v0.1 text only. Same rooms as the web app. Emulator default is the PC server at 10.0.2.2:8080.",
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
private fun ChatScreen(state: ChatUiState, vm: ChatViewModel) {
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
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("myChat", color = Teal, fontSize = 12.sp)
                Text(state.roomId, color = Mist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(state.status, color = Mute, fontSize = 12.sp)
            }
            TextButton(onClick = vm::leave) { Text("Leave", color = Teal) }
        }
        state.error?.let {
            Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(msg, mine = msg.authorId == state.authorId)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                enabled = !state.busy && state.draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Slate),
            ) { Text("Send") }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, mine: Boolean) {
    val body = when {
        msg.secure -> "[encrypted — open in web myChat]"
        msg.type == "drawing" -> "[sketch]"
        msg.type == "audio" -> "[voice note]"
        else -> msg.text.orEmpty().ifBlank { "[empty]" }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(msg.authorName, color = Mute, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            body,
            color = if (mine) Slate else Mist,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(if (mine) Teal else Panel, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
