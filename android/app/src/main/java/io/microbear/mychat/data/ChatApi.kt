package io.microbear.mychat.data

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ChatApi(
    private val gson: Gson = boardGson(),
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
            if (!res.isSuccessful) {
                val text = res.body?.string().orEmpty()
                val hint = when {
                    res.code == 502 || res.code == 503 ->
                        "Chat server could not send. Check InterServer is running, then retry."
                    text.contains("<html", ignoreCase = true) ->
                        "Chat server error (HTTP ${res.code})."
                    else -> "Send ${res.code}: ${text.take(160)}"
                }
                error(hint)
            }
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

    fun registerDevice(
        baseUrl: String,
        idToken: String,
        ssaid: String,
        displayName: String,
    ): DeviceAuthResponse {
        return postDevice(
            baseUrl,
            "/api/device/register",
            mapOf(
                "idToken" to idToken,
                "ssaid" to ssaid,
                "displayName" to displayName,
            ),
        )
    }

    fun requestAccess(
        baseUrl: String,
        mobile: String,
        ssaid: String,
        displayName: String,
    ): DeviceAuthResponse {
        return postDevice(
            baseUrl,
            "/api/device/request",
            mapOf(
                "mobile" to mobile,
                "ssaid" to ssaid,
                "displayName" to displayName,
            ),
        )
    }

    fun checkDevice(baseUrl: String, mobile: String, ssaid: String): DeviceAuthResponse {
        return postDevice(
            baseUrl,
            "/api/device/check",
            mapOf("mobile" to mobile, "ssaid" to ssaid),
        )
    }

    fun verifyDevice(baseUrl: String, idToken: String, ssaid: String): DeviceAuthResponse {
        return postDevice(
            baseUrl,
            "/api/device/verify",
            mapOf("idToken" to idToken, "ssaid" to ssaid),
        )
    }

    fun adminSession(baseUrl: String, mobile: String, ssaid: String): DeviceAuthResponse {
        return postDevice(
            baseUrl,
            "/api/admin/session",
            mapOf("mobile" to mobile, "ssaid" to ssaid),
        )
    }

    fun adminRequests(baseUrl: String, token: String): AdminListResponse {
        val req = Request.Builder()
            .url(join(baseUrl, "/api/admin/requests"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val parsed = runCatching { gson.fromJson(text, AdminListResponse::class.java) }.getOrNull()
            if (!res.isSuccessful) error(parsed?.error ?: "Admin ${res.code}: ${text.take(160)}")
            return parsed ?: error("Empty admin list")
        }
    }

    fun adminAdmit(baseUrl: String, token: String, mobile: String): DeviceAuthResponse {
        return postDevice(baseUrl, "/api/admin/admit", mapOf("mobile" to mobile), token)
    }

    fun adminReject(baseUrl: String, token: String, mobile: String): DeviceAuthResponse {
        return postDevice(baseUrl, "/api/admin/reject", mapOf("mobile" to mobile), token)
    }

    private fun postDevice(
        baseUrl: String,
        path: String,
        body: Map<String, String>,
        token: String? = null,
    ): DeviceAuthResponse {
        val payload = gson.toJson(body).toRequestBody(jsonType)
        val builder = Request.Builder().url(join(baseUrl, path)).post(payload)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val req = builder.build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val parsed = runCatching { gson.fromJson(text, DeviceAuthResponse::class.java) }.getOrNull()
            if (!res.isSuccessful) {
                val hint = parsed?.error?.takeIf { it.isNotBlank() }
                    ?: when {
                        res.code == 502 || res.code == 503 ->
                            "Chat server could not complete registration. Update InterServer and retry."
                        text.contains("<html", ignoreCase = true) ->
                            "Chat server error (HTTP ${res.code})."
                        else -> "Device ${res.code}: ${text.take(160)}"
                    }
                error(hint)
            }
            return parsed ?: error("Empty device response")
        }
    }

    private fun join(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return base + path
    }
}
