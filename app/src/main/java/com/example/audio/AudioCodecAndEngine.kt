package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** ITU-T G.711 Mu-Law codec for 2:1 voice compression (16-bit PCM <-> 8-bit Mu-Law). */
object MuLawCodec {
  private const val BIAS = 0x84
  private const val CLIP = 32635

  private val expLut =
    intArrayOf(
      0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
      4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
      5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
      6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
      6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 7, 7,
    )

  fun encodeSample(sampleInput: Short): Byte {
    var sample = sampleInput.toInt()
    val sign = (sample shr 8) and 0x80
    if (sign != 0) sample = -sample
    if (sample > CLIP) sample = CLIP
    sample += BIAS
    val exponent = expLut[(sample shr 7) and 0xFF]
    val mantissa = (sample shr (exponent + 3)) and 0x0F
    val encoded = (sign or (exponent shl 4) or mantissa).inv()
    return encoded.toByte()
  }

  fun decodeSample(muLawByte: Byte): Short {
    val u = muLawByte.toInt().inv() and 0xFF
    val sign = u and 0x80
    val exponent = (u shr 4) and 0x07
    val mantissa = u and 0x0F
    var sample = ((mantissa shl 3) + BIAS) shl exponent
    sample -= BIAS
    return (if (sign != 0) -sample else sample).toShort()
  }

  fun encodeBuffer(pcm: ShortArray, length: Int = pcm.size): ByteArray {
    val out = ByteArray(length)
    for (i in 0 until length) {
      out[i] = encodeSample(pcm[i])
    }
    return out
  }

  fun decodeBuffer(encoded: ByteArray, length: Int = encoded.size): ShortArray {
    val out = ShortArray(length)
    for (i in 0 until length) {
      out[i] = decodeSample(encoded[i])
    }
    return out
  }
}

class VoiceAudioEngine(private val context: Context) {

  companion object {
    private const val TAG = "VoiceAudioEngine"
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320 // 20ms at 16kHz
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private val _localLevel = MutableStateFlow(0f)
  val localLevel: StateFlow<Float> = _localLevel.asStateFlow()

  private val _remoteLevel = MutableStateFlow(0f)
  val remoteLevel: StateFlow<Float> = _remoteLevel.asStateFlow()

  @Volatile private var isMuted = false
  @Volatile private var isSpeaker = false
  @Volatile private var isRunning = false

  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var echoCanceler: AcousticEchoCanceler? = null
  private var noiseSuppressor: NoiseSuppressor? = null
  private var captureJob: Job? = null

  private var ringtone: Ringtone? = null
  private var ringbackJob: Job? = null
  private var toneGenerator: ToneGenerator? = null

  @SuppressLint("MissingPermission")
  fun startVoiceSession(
    speakerDefault: Boolean = false,
    onEncodedFrame: (ByteArray) -> Unit,
  ) {
    stopVoiceSession()
    stopTones()

    isMuted = false
    isSpeaker = speakerDefault
    isRunning = true

    configureAudioMode(inCall = true, speakerOn = speakerDefault)

    val minRecBytes =
      AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
        .coerceAtLeast(FRAME_SAMPLES * 4)

    val minPlayBytes =
      AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
        .coerceAtLeast(FRAME_SAMPLES * 4)

    try {
      val record =
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_COMMUNICATION,
          SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minRecBytes * 2,
        )

      if (record.state == AudioRecord.STATE_INITIALIZED) {
        if (AcousticEchoCanceler.isAvailable()) {
          echoCanceler =
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
          noiseSuppressor =
            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }
        audioRecord = record
        record.startRecording()
      } else {
        Log.e(TAG, "AudioRecord failed to initialize")
        record.release()
      }

      val track =
        AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(SAMPLE_RATE)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .build()
          )
          .setBufferSizeInBytes(minPlayBytes * 2)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build()

      if (track.state == AudioTrack.STATE_INITIALIZED) {
        audioTrack = track
        track.play()
      } else {
        Log.e(TAG, "AudioTrack failed to initialize")
        track.release()
      }

      captureJob =
        scope.launch {
          val pcmBuffer = ShortArray(FRAME_SAMPLES)
          while (isActive && isRunning) {
            val rec = audioRecord ?: break
            val read = rec.read(pcmBuffer, 0, FRAME_SAMPLES)
            if (read > 0) {
              if (isMuted) {
                _localLevel.value = 0f
                val silent = ByteArray(read) { 0xFF.toByte() }
                onEncodedFrame(silent)
              } else {
                _localLevel.value = computeRmsNormalized(pcmBuffer, read)
                val encoded = MuLawCodec.encodeBuffer(pcmBuffer, read)
                onEncodedFrame(encoded)
              }
            } else {
              delay(10)
            }
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "Error starting voice session", e)
    }
  }

  fun writePlaybackFrame(muLawBytes: ByteArray, length: Int = muLawBytes.size) {
    if (!isRunning || length <= 0) return
    val track = audioTrack ?: return
    val pcm = MuLawCodec.decodeBuffer(muLawBytes, length)
    _remoteLevel.value = computeRmsNormalized(pcm, pcm.size)
    try {
      track.write(pcm, 0, pcm.size)
    } catch (e: Exception) {
      Log.w(TAG, "AudioTrack write error", e)
    }
  }

  fun setMuted(muted: Boolean) {
    isMuted = muted
    if (muted) _localLevel.value = 0f
  }

  fun setSpeakerEnabled(enabled: Boolean) {
    isSpeaker = enabled
    configureAudioMode(inCall = true, speakerOn = enabled)
  }

  fun stopVoiceSession() {
    isRunning = false
    captureJob?.cancel()
    captureJob = null
    _localLevel.value = 0f
    _remoteLevel.value = 0f

    try {
      echoCanceler?.release()
    } catch (_: Exception) {}
    echoCanceler = null

    try {
      noiseSuppressor?.release()
    } catch (_: Exception) {}
    noiseSuppressor = null

    try {
      audioRecord?.stop()
      audioRecord?.release()
    } catch (_: Exception) {}
    audioRecord = null

    try {
      audioTrack?.stop()
      audioTrack?.release()
    } catch (_: Exception) {}
    audioTrack = null

    configureAudioMode(inCall = false, speakerOn = false)
  }

  fun startIncomingRingtone() {
    stopTones()
    try {
      val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
      ringtone =
        RingtoneManager.getRingtone(context, uri)?.apply {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            isLooping = true
          }
          play()
        }
    } catch (e: Exception) {
      Log.w(TAG, "Could not play ringtone", e)
    }
    startVibration()
  }

  fun startOutgoingRingback() {
    stopTones()
    ringbackJob =
      scope.launch {
        try {
          val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55)
          toneGenerator = tg
          while (isActive) {
            tg.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
            delay(3000)
          }
        } catch (e: Exception) {
          Log.w(TAG, "Ringback tone failed", e)
        }
      }
  }

  fun stopTones() {
    ringbackJob?.cancel()
    ringbackJob = null
    try {
      toneGenerator?.stopTone()
      toneGenerator?.release()
    } catch (_: Exception) {}
    toneGenerator = null

    try {
      ringtone?.stop()
    } catch (_: Exception) {}
    ringtone = null

    stopVibration()
  }

  private fun startVibration() {
    try {
      val vibrator = getVibrator() ?: return
      val pattern = longArrayOf(0, 600, 900)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
      } else {
        @Suppress("DEPRECATION") vibrator.vibrate(pattern, 0)
      }
    } catch (_: Exception) {}
  }

  private fun stopVibration() {
    try {
      getVibrator()?.cancel()
    } catch (_: Exception) {}
  }

  private fun getVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
        ?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

  private fun configureAudioMode(inCall: Boolean, speakerOn: Boolean) {
    try {
      if (inCall) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val targetType =
            if (speakerOn) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
          val targetDevice =
            audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
          if (targetDevice != null) {
            audioManager.setCommunicationDevice(targetDevice)
          } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerOn
          }
        } else {
          @Suppress("DEPRECATION")
          audioManager.isSpeakerphoneOn = speakerOn
        }
      } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          audioManager.clearCommunicationDevice()
        } else {
          @Suppress("DEPRECATION")
          audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
      }
    } catch (e: Exception) {
      Log.w(TAG, "AudioManager mode config warning", e)
    }
  }

  private fun computeRmsNormalized(buffer: ShortArray, length: Int): Float {
    if (length <= 0) return 0f
    var sumSq = 0.0
    for (i in 0 until length) {
      val s = buffer[i].toDouble()
      sumSq += s * s
    }
    val rms = sqrt(sumSq / length)
    return (rms / 6500.0).toFloat().coerceIn(0f, 1f)
  }
}
