package io.microbear.mychat

import io.microbear.mychat.data.WhiteboardState

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

fun quantizeBoard(n: Float): Float = (kotlin.math.round(n * 10f) / 10f)
