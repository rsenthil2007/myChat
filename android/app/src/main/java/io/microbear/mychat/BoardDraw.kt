package io.microbear.mychat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class BoardStroke(
    val type: String = "pen",
    val color: String,
    val size: Float,
    val points: List<Float>,
    val text: String = "",
)

fun io.microbear.mychat.data.BoardStrokeDto.toStroke(): BoardStroke = BoardStroke(
    type = t.ifBlank { "pen" },
    color = c.ifBlank { "#0f172a" },
    size = s,
    points = p,
    text = tx.orEmpty(),
)

fun BoardStroke.toPayload(): Map<String, Any?> = buildMap {
    put("t", type)
    put("c", color)
    put("s", size.toDouble())
    put("p", points)
    if (type == "text" && text.isNotBlank()) put("tx", text)
}

fun parseHexColor(raw: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(if (raw.startsWith("#")) raw else "#$raw"))
    } catch (_: Exception) {
        Color(0xFF0F172A)
    }
}

fun DrawScope.paintBoardStrokes(
    strokes: List<BoardStroke>,
    srcW: Int,
    srcH: Int,
) {
    val sx = if (srcW > 0) size.width / srcW else 1f
    val sy = if (srcH > 0) size.height / srcH else 1f
    strokes.forEach { paintBoardStroke(it, sx, sy) }
}

fun DrawScope.paintBoardStroke(stroke: BoardStroke, sx: Float = 1f, sy: Float = 1f) {
    val pts = stroke.points
    val col = parseHexColor(stroke.color)
    val sz = max(1.5f, stroke.size * min(sx, sy))
    if (stroke.type == "text") {
        if (stroke.text.isBlank() || pts.size < 2) return
        val x = pts[0] * sx
        val y = pts[1] * sy
        drawContext.canvas.nativeCanvas.drawText(
            stroke.text.take(80),
            x,
            y + max(12f, sz * 3f),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor(
                    try {
                        if (stroke.color.startsWith("#") && stroke.color.length == 7) stroke.color else "#0f172a"
                    } catch (_: Exception) {
                        "#0f172a"
                    },
                )
                textSize = max(12f, sz * 3f)
                isFakeBoldText = true
            },
        )
        return
    }
    if (pts.size < 2) return
    val erase = stroke.type == "erase"
    val strokeStyle = Stroke(
        width = if (erase) max(sz * 2f, 8f) else sz,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    val blend = if (erase) BlendMode.Clear else BlendMode.SrcOver
    val shapes = setOf("line", "arrow", "rect", "circle", "oval")
    if (stroke.type in shapes) {
        if (pts.size < 4) return
        val x1 = pts[0] * sx
        val y1 = pts[1] * sy
        val x2 = pts[2] * sx
        val y2 = pts[3] * sy
        when (stroke.type) {
            "rect" -> drawRect(
                color = col,
                topLeft = Offset(min(x1, x2), min(y1, y2)),
                size = Size(abs(x2 - x1).coerceAtLeast(0.01f), abs(y2 - y1).coerceAtLeast(0.01f)),
                style = strokeStyle,
                blendMode = blend,
            )
            "oval", "circle" -> {
                var left = min(x1, x2)
                var top = min(y1, y2)
                var w = abs(x2 - x1).coerceAtLeast(1f)
                var h = abs(y2 - y1).coerceAtLeast(1f)
                if (stroke.type == "circle") {
                    val side = max(w, h)
                    if (x2 < x1) left = x1 - side
                    if (y2 < y1) top = y1 - side
                    w = side
                    h = side
                }
                drawOval(
                    color = col,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = strokeStyle,
                    blendMode = blend,
                )
            }
            else -> {
                drawLine(col, Offset(x1, y1), Offset(x2, y2), strokeStyle.width, StrokeCap.Round, blendMode = blend)
                if (stroke.type == "arrow") {
                    val angle = atan2(y2 - y1, x2 - x1)
                    val head = max(10f, sz * 3f)
                    val path = Path()
                    path.moveTo(x2, y2)
                    path.lineTo(
                        x2 - head * cos(angle - Math.PI / 6).toFloat(),
                        y2 - head * sin(angle - Math.PI / 6).toFloat(),
                    )
                    path.lineTo(
                        x2 - head * cos(angle + Math.PI / 6).toFloat(),
                        y2 - head * sin(angle + Math.PI / 6).toFloat(),
                    )
                    path.close()
                    drawPath(path, col, blendMode = blend)
                }
            }
        }
        return
    }
    if (pts.size == 2) {
        drawCircle(col, strokeStyle.width / 2f, Offset(pts[0] * sx, pts[1] * sy), blendMode = blend)
        return
    }
    val path = Path()
    path.moveTo(pts[0] * sx, pts[1] * sy)
    var i = 2
    while (i + 1 < pts.size) {
        path.lineTo(pts[i] * sx, pts[i + 1] * sy)
        i += 2
    }
    drawPath(path, col, style = strokeStyle, blendMode = blend)
}
