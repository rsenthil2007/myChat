package io.microbear.mychat

import io.microbear.mychat.data.WhiteboardState
import kotlin.math.hypot

/** Shared logical board size — matches server `whiteboard.CANONICAL_*`. */
const val BOARD_LOGICAL_W = 1280
const val BOARD_LOGICAL_H = 1600

/** Same reference width SketchScreen uses so slider 4 ≈ 4px on a 300px pad. */
const val SKETCH_SIZE_REF = 300f
const val MAX_LOGICAL_PEN = 128f

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

/** On-screen stroke width — identical to SketchScreen. */
fun sketchViewPenSize(slider: Float, viewW: Float): Float =
    slider * (viewW.coerceAtLeast(SKETCH_SIZE_REF) / SKETCH_SIZE_REF)

/** Store SketchScreen's on-screen width in the shared logical canvas. */
fun toLogicalPenSize(slider: Float, viewW: Float, logicalW: Int): Float {
    val viewSize = sketchViewPenSize(slider, viewW)
    if (viewW <= 0f || logicalW <= 0) return viewSize.coerceIn(1f, MAX_LOGICAL_PEN)
    return (viewSize * logicalW / viewW).coerceIn(1f, MAX_LOGICAL_PEN)
}

fun viewPointsToLogical(
    points: List<Float>,
    viewW: Float,
    viewH: Float,
    logicalW: Int,
    logicalH: Int,
): List<Float> {
    if (points.size < 2) return emptyList()
    val out = ArrayList<Float>(points.size)
    var i = 0
    while (i + 1 < points.size) {
        out.add(toLogicalX(points[i], viewW, logicalW))
        out.add(toLogicalY(points[i + 1], viewH, logicalH))
        i += 2
    }
    return out
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
