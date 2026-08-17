package io.microbear.mychat.data

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ChatApi(
    private val gson: Gson = Gson(),
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun health(baseUrl: String): HealthResponse {
        val req = Request.Builder().url(join(baseUrl, "/api/health")).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Health ${res.code}")
            val body = res.body?.string().orEmpty()
            return gson.fromJson(body, HealthResponse::class.java)
        }
    }

    fun loadRoom(baseUrl: String, roomId: String): RoomSnapshot {
        val req = Request.Builder().url(join(baseUrl, "/api/rooms/$roomId")).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Room ${res.code}: ${res.body?.string()}")
            val body = res.body?.string().orEmpty()
            return gson.fromJson(body, RoomSnapshot::class.java)
        }
    }

    fun sendSecure(baseUrl: String, roomId: String, message: OutgoingSecure): RoomSnapshot {
        val payload = gson.toJson(message).toRequestBody(jsonType)
        val req = Request.Builder()
            .url(join(baseUrl, "/api/rooms/$roomId/messages"))
            .post(payload)
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Send ${res.code}: ${res.body?.string()}")
            val body = res.body?.string().orEmpty()
            return gson.fromJson(body, RoomSnapshot::class.java)
        }
    }

    fun clearRoom(baseUrl: String, roomId: String): RoomSnapshot {
        val req = Request.Builder()
            .url(join(baseUrl, "/api/rooms/$roomId/messages"))
            .delete()
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Clear ${res.code}: ${res.body?.string()}")
            val body = res.body?.string().orEmpty()
            return gson.fromJson(body, RoomSnapshot::class.java)
        }
    }

    fun whiteboardAction(
        baseUrl: String,
        roomId: String,
        action: String,
        body: Map<String, Any?>,
    ): WhiteboardActionResponse {
        val payload = gson.toJson(body).toRequestBody(jsonType)
        val req = Request.Builder()
            .url(join(baseUrl, "/api/rooms/$roomId/whiteboard/$action"))
            .post(payload)
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val parsed = runCatching { gson.fromJson(text, WhiteboardActionResponse::class.java) }.getOrNull()
            if (!res.isSuccessful) error(parsed?.error ?: "Board ${res.code}: $text")
            return parsed ?: error("Empty board response")
        }
    }

    fun registerDevice(baseUrl: String, mobile: String, ssaid: String): DeviceAuthResponse {
        return postDevice(baseUrl, "/api/device/register", mobile, ssaid)
    }

    fun verifyDevice(baseUrl: String, mobile: String, ssaid: String): DeviceAuthResponse {
        return postDevice(baseUrl, "/api/device/verify", mobile, ssaid)
    }

    private fun postDevice(
        baseUrl: String,
        path: String,
        mobile: String,
        ssaid: String,
    ): DeviceAuthResponse {
        val payload = gson.toJson(mapOf("mobile" to mobile, "ssaid" to ssaid)).toRequestBody(jsonType)
        val req = Request.Builder().url(join(baseUrl, path)).post(payload).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val parsed = runCatching { gson.fromJson(text, DeviceAuthResponse::class.java) }.getOrNull()
            if (!res.isSuccessful) error(parsed?.error ?: "Device ${res.code}: $text")
            return parsed ?: error("Empty device response")
        }
    }

    private fun join(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return base + path
    }
}
