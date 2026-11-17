package com.ether4o4.odcm.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.ether4o4.odcm.agent.CompanionService
import com.ether4o4.odcm.agent.Conversation
import com.ether4o4.odcm.voice.LiveVoiceService

/** Brief visible bootstrap for Android's while-in-use microphone permission. No recording dialog. */
class VoiceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var permissionPending = false
    private var starting = false
    private val timeout = Runnable { fail("Voice could not start. Open ODCM and try the mic again.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            CompanionService.hideForVoice()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        }
    }
    override fun onResume() {
        super.onResume()
        if (!permissionPending && !isFinishing) begin()
    }
    private fun begin() {
        if (starting || isFinishing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required for voice. Typing remains available."); return
        }
        starting = true
        val ack = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                handler.removeCallbacks(timeout)
                if (!isFinishing) finish()
            }
        }
        try {
            // Keep this activity visible until startForeground(MICROPHONE) has succeeded.
            startForegroundService(Intent(this, LiveVoiceService::class.java).putExtra(LiveVoiceService.EXTRA_READY, ack))
            handler.postDelayed(timeout, 5_000)
        } catch (_: Exception) { fail("Microphone could not start. Open ODCM and try again.") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        permissionPending = false
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) fail("Microphone permission is required for voice. Typing remains available.")
        // onResume starts the service only once this activity regains the foreground.
    }
    private fun fail(message: String) { Conversation.notice(message); finish() }
    override fun onStop() {
        super.onStop()
        if (!permissionPending && !isFinishing) { LiveVoiceService.stop(); finish() }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        CompanionService.restoreAfterVoice()
        super.onDestroy()
    }
}
