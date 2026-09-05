package io.microbear.mychat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BoardStrokeDtoTest {
    @Test
    fun adapterReadsLowercaseS() {
        val json = """{"t":"pen","c":"#dc2626","s":16.5,"sv":2,"p":[1,2,3,4]}"""
        val dto = boardGson().fromJson(json, BoardStrokeDto::class.java)
        assertEquals(16.5f, dto.penSize, 0.01f)
        assertEquals(2, dto.sv)
        assertEquals("#dc2626", dto.c)
        assertEquals(4, dto.p.size)
    }

    @Test
    fun adapterReadsSizeAlias() {
        val json = """{"t":"pen","c":"#0f172a","size":51.2,"p":[10,20,30,40]}"""
        val dto = boardGson().fromJson(json, BoardStrokeDto::class.java)
        assertEquals(51.2f, dto.penSize, 0.01f)
    }

    @Test
    fun nestedWhiteboardKeepsPenWidth() {
        val json = """
            {"w":1280,"h":1600,"layers":[{"authorId":"u1","strokes":
              [{"t":"pen","c":"#dc2626","s":51.2,"sv":2,"p":[1,2,3,4]}]}]}
        """.trimIndent()
        val board = boardGson().fromJson(json, WhiteboardState::class.java)
        val stroke = board.layers[0].strokes[0]
        assertEquals(51.2f, stroke.penSize, 0.05f)
        assertEquals(2, stroke.sv)
    }
}
