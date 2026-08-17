package io.microbear.mychat

import org.junit.Assert.assertEquals
import org.junit.Test

class BoardCoordsTest {
    @Test
    fun logicalMappingIsSymmetric() {
        val logicalW = 1280
        val logicalH = 1600
        val viewW = 640f
        val viewH = 800f
        val lx = toLogicalX(320f, viewW, logicalW)
        val ly = toLogicalY(400f, viewH, logicalH)
        assertEquals(640f, lx, 0.01f)
        assertEquals(800f, ly, 0.01f)
    }

    @Test
    fun boardLogicalSizeFallsBackToCanonical() {
        val empty = io.microbear.mychat.data.WhiteboardState()
        val (w, h) = boardLogicalSize(empty)
        assertEquals(BOARD_LOGICAL_W, w)
        assertEquals(BOARD_LOGICAL_H, h)
    }

    @Test
    fun boardLogicalSizeKeepsExistingRoomSize() {
        val board = io.microbear.mychat.data.WhiteboardState(w = 900, h = 500)
        val (w, h) = boardLogicalSize(board)
        assertEquals(900, w)
        assertEquals(500, h)
    }

    @Test
    fun toLogicalPenSizeMapsViewPixelsOntoTheBoard() {
        val logical = toLogicalPenSize(5f, 320f, 1280)
        assertEquals(20f, logical, 0.01f)
    }

    @Test
    fun appendBoardPointFillsLargeGaps() {
        val start = listOf(0f, 0f)
        val filled = appendBoardPoint(start, 10f, 0f, spacing = 3f)
        assertEquals(true, filled.size >= 6)
        assertEquals(10f, filled[filled.lastIndex - 1], 0.01f)
        assertEquals(0f, filled.last(), 0.01f)
    }
}
