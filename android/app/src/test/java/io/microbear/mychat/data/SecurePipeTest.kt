package io.microbear.mychat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePipeTest {
    @Test
    fun roundTripShortText() {
        val env = SecurePipe.sealText("Hi", "lobby")
        assertEquals(3, env.v)
        assertEquals(0, env.zip)
        assertEquals("Hi", SecurePipe.openText(env, "lobby"))
    }

    @Test
    fun roundTripCompressedText() {
        val text = ("stroke-point-" + "x".repeat(40) + "-").repeat(30)
        val env = SecurePipe.sealText(text, "r1")
        assertEquals(1, env.zip)
        assertEquals(text, SecurePipe.openText(env, "r1"))
    }

    @Test
    fun wrongRoomRejected() {
        val env = SecurePipe.sealText("secret-r1", "r1")
        try {
            SecurePipe.openText(env, "r2")
            throw AssertionError("r2 must not open r1 ciphertext")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("MAC"))
        }
    }

    @Test
    fun opensEnvelopeFromWebSecurePipe() {
        val env = SecurePipe.Envelope(
            v = 3,
            zip = 0,
            iv = "UHWlVm26FPxHfBIMSjJyHw==",
            mac = "fiqlBoFr+8rmn2kwoWBFLws5rrMskmT33RhI2YDfsdg=",
            data = "WhVss3qCgCxuYsrVWlxqnBTDZw0di//BxA==",
        )
        assertEquals("hello-from-web", SecurePipe.openText(env, "lobby"))
    }

    @Test
    fun newSealIsNotDeterministic() {
        val a = SecurePipe.sealText("same", "lobby")
        val b = SecurePipe.sealText("same", "lobby")
        assertNotEquals(a.iv, b.iv)
        assertNotEquals(a.data, b.data)
    }
}
