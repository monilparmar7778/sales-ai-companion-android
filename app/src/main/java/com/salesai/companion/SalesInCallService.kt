package com.salesai.companion

import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.telecom.Call
import android.telecom.InCallService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SalesInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private var activeCall: Call? = null

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                handleCallState(call, state)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        handleCallState(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        if (activeCall == call) stopCallRecording()
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        stopCallRecording()
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        super.onDestroy()
    }

    private fun handleCallState(call: Call, state: Int) {
        when (state) {
            Call.STATE_ACTIVE -> startCallRecording(call)
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                if (activeCall == call) stopCallRecording()
            }
        }
    }

    private fun startCallRecording(call: Call) {
        if (activeCall != null) return
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
        activeCall = call
    }

    private fun stopCallRecording() {
        stopService(Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP
        })
        activeCall = null
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
}
