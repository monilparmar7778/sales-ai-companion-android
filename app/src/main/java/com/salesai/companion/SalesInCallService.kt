package com.salesai.companion

import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.telecom.Call
import android.telecom.VideoProfile
import android.telecom.InCallService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SalesInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                handleCallState(call, state)
                SalesInCallActivity.refreshFromService()
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        handleCallState(call, call.state)
        showInCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        if (currentCall == call) {
            stopCallRecording()
            currentCall = null
        }
        SalesInCallActivity.refreshFromService()
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        stopCallRecording()
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        currentCall = null
        super.onDestroy()
    }

    private fun handleCallState(call: Call, state: Int) {
        when (state) {
            Call.STATE_ACTIVE -> startCallRecording(call)
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                if (currentCall == call) stopCallRecording()
            }
        }
    }

    private fun startCallRecording(call: Call) {
        if (recordingCall == call) return
        val file = File(recordingFolder(), callRecordingFileName(call))
        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START
            putExtra(CallRecordingService.EXTRA_FILE_PATH, file.absolutePath)
            putExtra(CallRecordingService.EXTRA_AUDIO_SOURCE, MediaRecorder.AudioSource.VOICE_CALL)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        recordingCall = call
    }

    private fun stopCallRecording() {
        stopService(Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP
        })
        recordingCall = null
    }

    private fun showInCallScreen() {
        val intent = Intent(this, SalesInCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun recordingFolder(): File {
        return File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SalesAIRecordings").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun callRecordingFileName(call: Call): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val handle = call.details?.handle
        val phone = handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
            .filter { it.isDigit() }
            .takeLast(10)
            .ifBlank { "unknown" }
        return "${timestamp}_default_dialer_${phone}_call.m4a"
    }

    companion object {
        var currentCall: Call? = null
            private set
        private var recordingCall: Call? = null

        fun answerCurrentCall() {
            currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        fun disconnectCurrentCall() {
            currentCall?.disconnect()
        }

        fun currentCallState(): Int? = currentCall?.state

        fun currentCallNumber(): String {
            return currentCall?.details?.handle?.schemeSpecificPart.orEmpty()
                .ifBlank { "Unknown number" }
        }
    }
}
