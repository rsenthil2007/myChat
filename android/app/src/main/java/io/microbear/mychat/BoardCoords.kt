package io.microbear.mychat

import io.microbear.mychat.data.WhiteboardState
import kotlin.math.hypot

/** Shared logical board size — matches server `whiteboard.CANONICAL_*`. */
const val BOARD_LOGICAL_W = 1280
const val BOARD_LOGICAL_H = 1600

fun boardLogicalSize(board: WhiteboardState): Pair<Int, Int> {
    val w = if (board.w > 0) board.w else BOARD_LOGICAL_W
    val h = if (board.h > 0) board.h else BOARD_LOGICAL_H
    return w to h
}

fun toLogicalX(localX: Float, viewW: Float, logicalW: Int): Float {
    if (viewW <= 0f || logicalW <= 0) return localX
    return localX * logicalW / viewW
}

fun toLogicalY(localY: Float, viewH: Float, logicalH: Int): Float {
    if (viewH <= 0f || logicalH <= 0) return localY
    return localY * logicalH / viewH
}

/** Map on-screen pen width into the shared 1280-wide logical canvas. */
fun toLogicalPenSize(viewPx: Float, viewW: Float, logicalW: Int): Float {
    if (viewW <= 0f || logicalW <= 0) return viewPx.coerceIn(1f, 48f)
    return (viewPx * logicalW / viewW).coerceIn(1f, 48f)
}

fun quantizeBoard(n: Float): Float = (kotlin.math.round(n * 10f) / 10f)

/** Insert midpoints so a fast swipe does not become a trail of dots. */
fun appendBoardPoint(
    points: List<Float>,
    x: Float,
    y: Float,
    spacing: Float = 3f,
): List<Float> {
    val qx = quantizeBoard(x)
    val qy = quantizeBoard(y)
    if (points.size < 2) return points + listOf(qx, qy)
    val x0 = points[points.lastIndex - 1]
    val y0 = points.last()
    val dx = qx - x0
    val dy = qy - y0
    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (dist < 0.4f) return points
    val step = spacing.coerceAtLeast(1.5f)
    if (dist <= step) return points + listOf(qx, qy)
    val n = (dist / step).toInt().coerceAtMost(80)
    val extra = ArrayList<Float>(points.size + n * 2 + 2)
    extra.addAll(points)
    for (i in 1..n) {
        val t = i.toFloat() / (n + 1)
        extra.add(quantizeBoard(x0 + dx * t))
        extra.add(quantizeBoard(y0 + dy * t))
    }
    extra.add(qx)
    extra.add(qy)
    return extra
}
