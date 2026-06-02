package com.salesai.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.File

class CallRecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                intent.getStringExtra(EXTRA_FILE_PATH).orEmpty(),
                intent.getIntExtra(EXTRA_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC)
            )
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(filePath: String, audioSource: Int) {
        if (filePath.isBlank()) {
            stopSelf()
            return
        }
        try {
            stopRecording()
            File(filePath).parentFile?.mkdirs()
            if (audioSource == MediaRecorder.AudioSource.MIC) {
                enableSpeakerAssist()
            }
            startForeground(NOTIFICATION_ID, recordingNotification())
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            mediaRecorder.setAudioSource(audioSource)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(128000)
            mediaRecorder.setAudioSamplingRate(44100)
            mediaRecorder.setOutputFile(filePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
        } catch (_: Exception) {
            recorder = null
            restoreSpeakerAssist()
            stopSelf()
        }
    }

    private fun stopRecording() {
        val activeRecorder = recorder
        if (activeRecorder != null) {
            try {
                activeRecorder.stop()
            } catch (_: Exception) {
            } finally {
                activeRecorder.release()
                recorder = null
            }
        }
        restoreSpeakerAssist()
    }

    private fun enableSpeakerAssist() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (previousAudioMode == null) previousAudioMode = audioManager.mode
            if (previousSpeakerphoneOn == null) previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
        } catch (_: Exception) {
            previousAudioMode = null
            previousSpeakerphoneOn = null
        }
    }

    private fun restoreSpeakerAssist() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousSpeakerphoneOn?.let { audioManager.isSpeakerphoneOn = it }
            previousAudioMode?.let { audioManager.mode = it }
        } catch (_: Exception) {
        } finally {
            previousAudioMode = null
            previousSpeakerphoneOn = null
        }
    }

    private fun recordingNotification(): Notification {
        val channelId = "sales_ai_call_recording"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Call recording", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        return Notification.Builder(this, channelId)
            .setContentTitle("Sales AI recording")
            .setContentText("Recording call audio for upload")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.salesai.companion.action.START_CALL_RECORDING"
        const val ACTION_STOP = "com.salesai.companion.action.STOP_CALL_RECORDING"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_AUDIO_SOURCE = "audio_source"
        private const val NOTIFICATION_ID = 42
    }
}
