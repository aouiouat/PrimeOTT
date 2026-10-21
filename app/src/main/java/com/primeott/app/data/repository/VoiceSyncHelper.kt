package com.primeott.app.data.repository

import com.primeott.app.data.remote.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class IncomingVoicePacket(
    val id: Long,
    val senderId: String,
    val senderName: String,
    val pcmBase64: String
)

object VoiceSyncHelper {
    suspend fun syncVoice(
        roomId: String,
        deviceId: String,
        userName: String,
        lastVoiceId: Long,
        outgoingPcmBase64: String
    ): List<IncomingVoicePacket> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("room_id", roomId)
                put("device_id", deviceId)
                put("user_name", userName)
                put("last_voice_id", lastVoiceId)
                put("pcm_data", outgoingPcmBase64)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${SessionManager.CLOUDFLARE_API_URL}/api/watch/voice-sync")
                .post(body)
                .build()

            OttRepository.okHttpClient.newCall(req).execute().use { res ->
                val str = res.body?.string() ?: "{}"
                val json = JSONObject(str)
                val arr = json.optJSONArray("packets") ?: return@withContext emptyList()
                val list = ArrayList<IncomingVoicePacket>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        IncomingVoicePacket(
                            id = o.optLong("id"),
                            senderId = o.optString("sender_id"),
                            senderName = o.optString("sender_name"),
                            pcmBase64 = o.optString("pcm_data")
                        )
                    )
                }
                return@withContext list
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }
}
