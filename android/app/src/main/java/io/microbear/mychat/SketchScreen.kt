package io.microbear.mychat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Slate = Color(0xFF0F172A)
private val Panel = Color(0xFF1E293B)
private val Teal = Color(0xFF2DD4BF)
private val Mist = Color(0xFFE2E8F0)
private val Mute = Color(0xFF94A3B8)

private val PenColors = listOf(
    "#0f172a" to Color(0xFF0F172A),
    "#2dd4bf" to Color(0xFF2DD4BF),
    "#f87171" to Color(0xFFF87171),
    "#fbbf24" to Color(0xFFFBBF24),
    "#60a5fa" to Color(0xFF60A5FA),
    "#a78bfa" to Color(0xFFA78BFA),
)

@Composable
fun SketchScreen(busy: Boolean, onCancel: () -> Unit, onSend: (Int, Int, List<SketchStroke>) -> Unit) {
    var colorHex by remember { mutableStateOf("#0f172a") }
    var sizeSlider by remember { mutableFloatStateOf(4f) }
    var strokes by remember { mutableStateOf(listOf<SketchStroke>()) }
    var current by remember { mutableStateOf<List<Float>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val size = sizeSlider * (canvasSize.width.toFloat().coerceAtLeast(300f) / 300f)
    val colorMap = PenColors.toMap()

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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Mute) }
            Text("Sketch", color = Mist, fontSize = 16.sp)
            Button(
                onClick = {
                    val all = if (current.size >= 2) strokes + SketchStroke(colorHex, size, current) else strokes
                    if (all.isEmpty() || canvasSize.width < 8) return@Button
                    onSend(canvasSize.width, canvasSize.height, all)
                },
                enabled = !busy && (strokes.isNotEmpty() || current.size >= 2),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Slate),
            ) { Text("Send") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PenColors.forEach { (hex, color) ->
                Box(
                    modifier = Modifier
                        .size(if (hex == colorHex) 28.dp else 22.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(if (hex == colorHex) 2.dp else 0.dp, Teal, CircleShape)
                        .clickable { colorHex = hex },
                )
            }
            Slider(
                value = sizeSlider,
                onValueChange = { sizeSlider = it },
                valueRange = 2f..24f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal),
            )
            TextButton(onClick = {
                strokes = emptyList()
                current = emptyList()
            }) { Text("Clear", color = Mute) }
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color.White)
                .onSizeChanged { canvasSize = it }
                .pointerInput(colorHex, sizeSlider) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            current = listOf(offset.x, offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            current = current + listOf(change.position.x, change.position.y)
                        },
                        onDragEnd = {
                            if (current.size >= 2) {
                                strokes = strokes + SketchStroke(colorHex, size, current)
                            }
                            current = emptyList()
                        },
                    )
                },
        ) {
            strokes.forEach { stroke ->
                paintFreehandStroke(
                    colorMap[stroke.color] ?: Color(0xFF0F172A),
                    stroke.size,
                    stroke.points,
                )
            }
            if (current.size >= 2) {
                paintFreehandStroke(
                    colorMap[colorHex] ?: Color(0xFF0F172A),
                    size,
                    current,
                )
            }
        }
    }
}
