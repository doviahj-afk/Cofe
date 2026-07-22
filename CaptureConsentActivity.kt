package com.joshua.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CaptureConsentActivity : Activity() {

    private val REQUEST_CODE = 9821
    private val PERM_REQUEST_CODE = 9822

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST_CODE)
        } else {
            launchCapture()
        }
    }

    private fun missingPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        if (Prefs.readAll(prefs).mic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        return needed
    }

    private fun launchCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            // Proceed regardless of the outcome: worst case the recording
            // starts without audio / without a visible notification, rather
            // than silently doing nothing when the tile is tapped.
            launchCapture()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
            val settings = Prefs.readAll(prefs)
            val svcIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, data)
                putExtra(RecordingService.EXTRA_FPS, settings.fps)
                putExtra(RecordingService.EXTRA_QUALITY, settings.quality)
                putExtra(RecordingService.EXTRA_STORAGE_MODE, settings.storageMode)
                putExtra(RecordingService.EXTRA_STORAGE_PATH, settings.storagePath)
                putExtra(RecordingService.EXTRA_MIC, settings.mic)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, svcIntent)
        }
        finish()
    }
}
