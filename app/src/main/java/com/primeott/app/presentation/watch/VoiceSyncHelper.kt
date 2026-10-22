package com.primeott.app.presentation.watch

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VoiceSyncHelper - مزامنة الصوت السحابي واللحظي لغرف Watch Together
 */
object VoiceSyncHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    var baseUrl: String = "https://prime-ott-panel.prime-ott-stream.workers.dev"

    fun pushAudioChunk(
        roomId: String,
        userId: String,
        userName: String,
        pcmData: ByteArray,
        authToken: String = ""
    ): Boolean {
        return try {
            val encoded = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("roomId", roomId)
                put("userId", userId)
                put("userName", userName)
                put("audio", encoded)
                put("ts", System.currentTimeMillis())
            }
            val reqBuilder = Request.Builder()
                .url("$baseUrl/voice/push")
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            if (authToken.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $authToken")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    fun pullAudioChunks(
        roomId: String,
        excludeUserId: String,
        sinceTs: Long,
        mutedUserIds: Set<String> = emptySet()
    ): List<Pair<String, ByteArray>> {
        val list = mutableListOf<Pair<String, ByteArray>>()
        try {
            val url = "$baseUrl/voice/pull?roomId=$roomId&since=$sinceTs"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val arr = JSONObject(body).optJSONArray("chunks") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val senderId = item.optString("userId")
                    if (senderId.isNotEmpty() && senderId != excludeUserId && !mutedUserIds.contains(senderId)) {
                        val b64 = item.optString("audio")
                        if (b64.isNotEmpty()) {
                            val bytes = Base64.decode(b64, Base64.NO_WRAP)
                            list.add(senderId to bytes)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}