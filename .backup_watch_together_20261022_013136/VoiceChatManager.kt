package com.primeott.app.presentation.watch

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.primeott.app.data.repository.VoiceSyncHelper
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class VoiceChatManager(
    private val context: Context,
    private val roomIdProvider: () -> String,
    private val deviceId: String,
    private val userName: String,
    private val onRemoteSpeakerActive: (String) -> Unit,
    private val onStateChanged: (Boolean, Boolean, String) -> Unit
) {
    private var isConnected = false
    private var isMuted = false
    private var voiceLoopJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var lastVoicePacketId: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 8000

    @SuppressLint("MissingPermission")
    fun connectVoiceSession() {
        if (isConnected) return
        isConnected = true
        isMuted = false // يبدأ الميكروفون مفعلاً بمجرد ضغط المستخدم على اتصال صوتي

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {}

        initAudioHardware()
        startCloudVoiceSyncLoop()
        onStateChanged(true, isMuted, "🟢 متصل بالصوت الجماعي (الميكروفون مفعل 🎙️)")
    }

    @SuppressLint("MissingPermission")
    private fun initAudioHardware() {
        try {
            val minOutBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOutBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().apply { play() }

            val minInBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minInBuf * 2
            )

            // تفعيل مانع الصدى والضجيج في الهاتف إن توفر
            audioRecord?.audioSessionId?.let { sessionId ->
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(sessionId)?.enabled = true
                }
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(sessionId)?.enabled = true
                }
            }

            audioRecord?.startRecording()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCloudVoiceSyncLoop() {
        voiceLoopJob?.cancel()
        voiceLoopJob = scope.launch {
            val readBuffer = ByteArray(4800) // حوالي 0.3 ثانية من الصوت
            while (isActive && isConnected) {
                val roomId = roomIdProvider()
                if (roomId.isEmpty()) {
                    delay(300)
                    continue
                }

                var outgoingBase64 = ""
                if (!isMuted && audioRecord != null) {
                    val outStream = ByteArrayOutputStream()
                    var totalRead = 0
                    val startMs = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startMs < 320 && totalRead < 6400) {
                        val r = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                        if (r > 0) {
                            outStream.write(readBuffer, 0, r)
                            totalRead += r
                        } else {
                            break
                        }
                    }
                    val rawBytes = outStream.toByteArray()
                    // إرسال الحزمة فقط إذا كان هناك صوت فعلي لتوفير الإنترنت
                    if (rawBytes.isNotEmpty() && hasVoiceActivity(rawBytes)) {
                        outgoingBase64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                    }
                } else {
                    delay(320)
                }

                val incomingPackets = VoiceSyncHelper.syncVoice(
                    roomId = roomId,
                    deviceId = deviceId,
                    userName = userName,
                    lastVoiceId = lastVoiceId(),
                    outgoingPcmBase64 = outgoingBase64
                )

                for (pkt in incomingPackets) {
                    if (pkt.id > lastVoicePacketId) {
                        lastVoicePacketId = pkt.id
                    }
                    if (pkt.senderId != deviceId && pkt.pcmBase64.isNotEmpty()) {
                        onRemoteSpeakerActive(pkt.senderName)
                        playPcmBase64(pkt.pcmBase64)
                    }
                }
            }
        }
    }

    private fun lastVoiceId(): Long = lastVoicePacketId

    private fun hasVoiceActivity(pcmBytes: ByteArray): Boolean {
        var sum = 0L
        var samples = 0
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val sample = (pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)
            sum += abs(sample)
            samples++
            i += 4
        }
        val avg = if (samples > 0) sum / samples else 0
        return avg > 180 // عتبة حساسية الصوت
    }

    fun playPcmBase64(base64Pcm: String) {
        if (!isConnected) return
        try {
            val bytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
            audioTrack?.write(bytes, 0, bytes.size)
        } catch (e: Exception) {}
    }

    fun toggleMute(): Boolean {
        if (!isConnected) return true
        isMuted = !isMuted
        val msg = if (isMuted) "🔇 تم كتم الميكروفون" else "🎙️ الميكروفون مفعل — أصدقاؤك يسمعونك الآن"
        onStateChanged(isConnected, isMuted, msg)
        return isMuted
    }

    fun disconnectVoice() {
        isConnected = false
        isMuted = true
        voiceLoopJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        onStateChanged(false, true, "⚪ تم قطع الاتصال الصوتي")
    }
}
