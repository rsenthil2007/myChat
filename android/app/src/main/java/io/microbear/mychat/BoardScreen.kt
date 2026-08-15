package io.microbear.mychat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Panel = Color(0xFF1E293B)
private val Teal = Color(0xFF2DD4BF)
private val Mist = Color(0xFFE2E8F0)
private val Mute = Color(0xFF94A3B8)
private val Danger = Color(0xFFF87171)

@Composable
fun BoardScreen(state: ChatUiState, vm: ChatViewModel, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<List<Float>>(emptyList()) }
    val mine = state.board.layers.find { it.authorId == state.authorId }
    val colorName = state.board.palette.find {
        it.hex.equals(state.boardColor, ignoreCase = true)
    }?.name ?: state.boardColor
    val others = state.board.layers.filter { it.authorId != state.authorId }

    Column(modifier.fillMaxSize()) {
        Text(
            if (mine != null) "Your color: $colorName · Pen and eraser. Undo and Clear mine affect only you."
            else "Open the board to claim a unique color.",
            color = Mute,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (state.board.layers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.board.layers.forEach { layer ->
                    val you = layer.authorId == state.authorId
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(layer.assignedColor)),
                        )
                        Text(
                            (layer.authorName.ifBlank { "Guest" }) + if (you) " (you)" else "",
                            color = if (you) Teal else Mist,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(state.boardColor))
                    .border(1.dp, Teal, CircleShape),
            )
            ToolChip("Pen", state.boardTool == "pen") { vm.onBoardTool("pen") }
            ToolChip("Eraser", state.boardTool == "erase") { vm.onBoardTool("erase") }
            Slider(
                value = state.boardPenSize,
                onValueChange = vm::onBoardPenSize,
                valueRange = 2f..24f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal),
            )
            TextButton(
                onClick = vm::undoBoard,
                enabled = state.boardMineStrokes.isNotEmpty(),
            ) { Text("Undo", color = if (state.boardMineStrokes.isEmpty()) Mute else Teal) }
            TextButton(
                onClick = vm::clearBoardMine,
                enabled = state.boardMineStrokes.isNotEmpty(),
            ) { Text("Clear mine", color = Mute) }
            TextButton(onClick = vm::askClearBoard) { Text("Clear all", color = Danger) }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color.White)
                .onSizeChanged { vm.onBoardCanvasSize(it.width, it.height) }
                .pointerInput(state.boardColor, state.boardPenSize, state.boardTool) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            vm.setBoardDragging(true)
                            current = listOf(offset.x, offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            current = current + listOf(change.position.x, change.position.y)
                        },
                        onDragEnd = {
                            if (current.size >= 2) {
                                vm.addBoardStroke(
                                    BoardStroke(
                                        type = state.boardTool,
                                        color = state.boardColor,
                                        size = state.boardPenSize,
                                        points = current,
                                    ),
                                )
                            } else {
                                vm.setBoardDragging(false)
                            }
                            current = emptyList()
                        },
                        onDragCancel = {
                            current = emptyList()
                            vm.setBoardDragging(false)
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                others.forEach { layer ->
                    paintBoardStrokes(
                        layer.strokes.map { it.toStroke() },
                        state.board.w,
                        state.board.h,
                    )
                }
                val srcW = if (state.board.w > 0) state.board.w else size.width.toInt()
                val srcH = if (state.board.h > 0) state.board.h else size.height.toInt()
                paintBoardStrokes(state.boardMineStrokes, srcW, srcH)
                if (current.size >= 2) {
                    paintBoardStroke(
                        BoardStroke(state.boardTool, state.boardColor, state.boardPenSize, current),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Teal else Mute,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    )
}
