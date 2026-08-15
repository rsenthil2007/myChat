package io.microbear.mychat.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Same room-code pipe as `js/crypto-pipe.js`:
 * SHA-256 keystream + HMAC-SHA256, optional deflate-raw, envelope v3.
 */
object SecurePipe {
    private const val APP_PEPPER = "mychat-secure-v3"
    private const val MIN_COMPRESS = 64
    private val utf8 = Charsets.UTF_8
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val random = SecureRandom()
    private val keyCache = ConcurrentHashMap<String, Keys>()

    data class Envelope(
        val v: Int,
        val zip: Int,
        val iv: String,
        val mac: String,
        val data: String,
    )

    private data class Keys(val encKey: ByteArray, val macKey: ByteArray)

    fun sealText(text: String, roomId: String): Envelope = sealJson(mapOf("text" to text), roomId)

    fun sealJson(payload: Any, roomId: String): Envelope {
        val raw = gson.toJson(payload).toByteArray(utf8)
        val packed = compress(raw)
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val keys = deriveKeys(roomId)
        val cipher = xor(packed.bytes, keystream(keys.encKey, iv, packed.bytes.size))
        val zipByte = byteArrayOf(if (packed.zip) 1 else 0)
        val mac = hmacSha256(keys.macKey, concat(zipByte, iv, cipher))
        return Envelope(
            v = 3,
            zip = if (packed.zip) 1 else 0,
            iv = b64(iv),
            mac = b64(mac),
            data = b64(cipher),
        )
    }

    fun open(envelope: Envelope, roomId: String): JsonObject {
        if (envelope.iv.isBlank() || envelope.data.isBlank() || envelope.mac.isBlank()) {
            error("Invalid envelope")
        }
        if (envelope.v < 3) error("Old message format — clear room and resend")
        val iv = unb64(envelope.iv)
        val cipher = unb64(envelope.data)
        val mac = unb64(envelope.mac)
        val zip = if (envelope.zip != 0) 1 else 0
        val keys = deriveKeys(roomId)
        val expect = hmacSha256(keys.macKey, concat(byteArrayOf(zip.toByte()), iv, cipher))
        var diff = 0
        for (i in 0 until 32) {
            val got = if (i < mac.size) mac[i].toInt() and 0xff else 0
            diff = diff or ((expect[i].toInt() and 0xff) xor got)
        }
        if (diff != 0) error("MAC mismatch")
        val packed = xor(cipher, keystream(keys.encKey, iv, cipher.size))
        val plain = decompress(packed, zip == 1)
        return gson.fromJson(String(plain, utf8), JsonObject::class.java)
            ?: error("Empty payload")
    }

    fun openText(envelope: Envelope, roomId: String): String {
        val body = open(envelope, roomId)
        return body.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }

    fun clearKeyCache() {
        keyCache.clear()
    }

    private fun deriveKeys(roomId: String): Keys {
        val id = roomId.ifBlank { "lobby" }
        return keyCache.getOrPut(id) {
            val md = MessageDigest.getInstance("SHA-256")
            var x = "$APP_PEPPER|$id|mychat-salt-v3".toByteArray(utf8)
            repeat(20_000) { x = md.digest(x) }
            val encKey = x
            val macKey = md.digest(concat("mac|".toByteArray(utf8), encKey))
            Keys(encKey, macKey)
        }
    }

    private fun keystream(encKey: ByteArray, iv: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        val counter = ByteArray(4)
        val md = MessageDigest.getInstance("SHA-256")
        var offset = 0
        var n = 0
        while (offset < length) {
            counter[0] = (n ushr 24).toByte()
            counter[1] = (n ushr 16).toByte()
            counter[2] = (n ushr 8).toByte()
            counter[3] = n.toByte()
            md.reset()
            md.update(encKey)
            md.update(iv)
            md.update(counter)
            val block = md.digest()
            val take = minOf(32, length - offset)
            System.arraycopy(block, 0, out, offset, take)
            offset += take
            n += 1
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private data class Packed(val bytes: ByteArray, val zip: Boolean)

    private fun compress(bytes: ByteArray): Packed {
        if (bytes.size < MIN_COMPRESS) return Packed(bytes, false)
        return try {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(bytes)
            deflater.finish()
            val buf = ByteArray(4096)
            val out = ByteArrayOutputStream()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            deflater.end()
            val compressed = out.toByteArray()
            if (compressed.size >= bytes.size - 8) Packed(bytes, false)
            else Packed(compressed, true)
        } catch (_: Exception) {
            Packed(bytes, false)
        }
    }

    private fun decompress(bytes: ByteArray, zip: Boolean): ByteArray {
        if (!zip) return bytes
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val buf = ByteArray(4096)
        val out = ByteArrayOutputStream()
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) error("Truncated compressed payload")
            if (n > 0) out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size)
        for (i in a.indices) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun unb64(value: String): ByteArray =
        Base64.getDecoder().decode(value.trim())
}

fun ChatMessage.toEnvelope(): SecurePipe.Envelope? {
    if (!secure || iv.isNullOrBlank() || mac.isNullOrBlank() || data.isNullOrBlank()) return null
    return SecurePipe.Envelope(v = v, zip = zip, iv = iv, mac = mac, data = data)
}
