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
    fun sketchViewPenSizeMatchesRoomPad() {
        assertEquals(4f, sketchViewPenSize(4f, 300f), 0.01f)
        assertEquals(8f, sketchViewPenSize(4f, 600f), 0.01f)
    }

    @Test
    fun toLogicalPenSizeKeepsSketchScreenThickness() {
        val viewW = 360f
        val slider = 4f
        val viewSize = sketchViewPenSize(slider, viewW)
        val logical = toLogicalPenSize(slider, viewW, 1280)
        val drawnBack = logical * viewW / 1280f
        assertEquals(viewSize, drawnBack, 0.02f)
        assertEquals(4f * 360f / 300f, viewSize, 0.01f)
    }

    @Test
    fun viewPointsToLogicalRoundTrip() {
        val view = listOf(10f, 20f, 30f, 40f)
        val logical = viewPointsToLogical(view, 320f, 400f, 1280, 1600)
        assertEquals(40f, logical[0], 0.01f)
        assertEquals(80f, logical[1], 0.01f)
        assertEquals(120f, logical[2], 0.01f)
        assertEquals(160f, logical[3], 0.01f)
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
