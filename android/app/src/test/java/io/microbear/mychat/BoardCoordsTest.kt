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
}
