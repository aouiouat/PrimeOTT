package com.primeott.app.presentation.watch

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * VoiceChatManager - محرك الدردشة الصوتية اللحظية مع إلغاء الصدى والضجيج،
 * كشف المتحدث، وكتم المشاركين الفردي أو الجماعي.
 */
class VoiceChatManager(
    private val context: Context,
    private val roomId: String,
    private val userId: String,
    private val userName: String = "مشاهد",
    private val onSpeakingStateChanged: ((Boolean, Int) -> Unit)? = null
) {

    private val sampleRate = 16000
    private val isRunning = AtomicBoolean(false)
    private val isMicMuted = AtomicBoolean(true)
    private val isHostForceMuted = AtomicBoolean(false)
    private val isSpeakerMuted = AtomicBoolean(false)

    private val locallyMutedParticipants = ConcurrentHashMap.newKeySet<String>()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    var onOutgoingVoiceFrame: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startVoiceChat() {
        if (isRunning.getAndSet(true)) return

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = true

            val minRecBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf * 2
            )

            val sessionId = audioRecord?.audioSessionId ?: 0
            if (sessionId != 0) {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                }
            }

            val minPlayBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                .setBufferSizeInBytes(minPlayBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord?.startRecording()
            audioTrack?.play()

            recordThread = Thread {
                val buf = ByteArray(1600)
                while (isRunning.get()) {
                    try {
                        if (isMicMuted.get() || isHostForceMuted.get()) {
                            onSpeakingStateChanged?.invoke(false, 0)
                            Thread.sleep(80)
                            continue
                        }
                        val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                        if (read > 0) {
                            val chunk = buf.copyOf(read)
                            val level = calculateRmsLevel(chunk)
                            val speaking = level > 350
                            onSpeakingStateChanged?.invoke(speaking, level)
                            if (speaking) {
                                onOutgoingVoiceFrame?.invoke(chunk)
                                VoiceSyncHelper.pushAudioChunk(roomId, userId, userName, chunk)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }.apply { isDaemon = true; start() }

            playThread = Thread {
                var lastTs = System.currentTimeMillis() - 1000
                while (isRunning.get()) {
                    try {
                        if (!isSpeakerMuted.get()) {
                            val chunks = VoiceSyncHelper.pullAudioChunks(
                                roomId = roomId,
                                excludeUserId = userId,
                                sinceTs = lastTs,
                                mutedUserIds = locallyMutedParticipants
                            )
                            lastTs = System.currentTimeMillis()
                            for ((senderId, pcm) in chunks) {
                                if (!locallyMutedParticipants.contains(senderId)) {
                                    audioTrack?.write(pcm, 0, pcm.size)
                                }
                            }
                        }
                        Thread.sleep(120)
                    } catch (_: Exception) {
                    }
                }
            }.apply { isDaemon = true; start() }

        } catch (_: Exception) {
            isRunning.set(false)
        }
    }

    fun playIncomingWebSocketVoice(senderId: String, pcmBytes: ByteArray) {
        if (!isRunning.get() || isSpeakerMuted.get() || senderId == userId) return
        if (locallyMutedParticipants.contains(senderId)) return
        try {
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (_: Exception) {
        }
    }

    private fun calculateRmsLevel(pcm: ByteArray): Int {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
            count++
            i += 2
        }
        return if (count > 0) sqrt(sum / count).toInt() else 0
    }

    fun setMuted(muted: Boolean) {
        if (isHostForceMuted.get() && !muted) return
        isMicMuted.set(muted)
        if (muted) onSpeakingStateChanged?.invoke(false, 0)
    }

    fun setHostForceMuted(forceMuted: Boolean) {
        isHostForceMuted.set(forceMuted)
        if (forceMuted) {
            isMicMuted.set(true)
            onSpeakingStateChanged?.invoke(false, 0)
        }
    }

    fun isMuted(): Boolean = isMicMuted.get() || isHostForceMuted.get()

    fun isForceMutedByHost(): Boolean = isHostForceMuted.get()

    fun setSpeakerMuted(muted: Boolean) {
        isSpeakerMuted.set(muted)
    }

    fun isSpeakerMuted(): Boolean = isSpeakerMuted.get()

    fun setParticipantMutedLocally(targetUserId: String, muted: Boolean) {
        if (muted) locallyMutedParticipants.add(targetUserId)
        else locallyMutedParticipants.remove(targetUserId)
    }

    fun isParticipantMutedLocally(targetUserId: String): Boolean =
        locallyMutedParticipants.contains(targetUserId)

    fun stopVoiceChat() {
        isRunning.set(false)
        try { recordThread?.interrupt() } catch (_: Exception) {}
        try { playThread?.interrupt() } catch (_: Exception) {}
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
    }

    fun release() = stopVoiceChat()
}