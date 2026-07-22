package com.joshua.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import com.getcapacitor.PermissionState
import com.getcapacitor.PluginMethod
import com.getcapacitor.Plugin
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "ScreenRecorder",
    permissions = [
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "microphone"),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications")
    ]
)
class ScreenRecorderPlugin : Plugin() {

    private lateinit var prefs: SharedPreferences

    // Fired whenever RecordingService's actual state changes (including async
    // failures after startRecording() already resolved), so the JS UI can
    // never get stuck showing a fake "recording" state.
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val recording = intent?.getBooleanExtra("recording", false) ?: false
            val ret = JSObject()
            ret.put("recording", recording)
            notifyListeners("stateChanged", ret)
        }
    }

    override fun load() {
        super.load()
        prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(stateReceiver, IntentFilter(RecordingService.ACTION_STATE_CHANGED))
    }

    override fun handleOnDestroy() {
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(stateReceiver)
        } catch (e: Exception) { /* ignore */ }
        super.handleOnDestroy()
    }

    @PluginMethod
    fun startRecording(call: PluginCall) {
        if (RecordingService.isRunning) {
            val ret = JSObject()
            ret.put("started", false)
            ret.put("error", "already_recording")
            call.resolve(ret)
            return
        }

        val needsMic = Prefs.readAll(prefs).mic
        val neededAliases = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != PermissionState.GRANTED) {
            neededAliases.add("notifications")
        }
        if (needsMic && getPermissionState("microphone") != PermissionState.GRANTED) {
            neededAliases.add("microphone")
        }

        if (neededAliases.isNotEmpty()) {
            saveCall(call)
            requestPermissionForAliases(neededAliases.toTypedArray(), call, "permissionCallback")
            return
        }

        beginCapture(call)
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        // Proceed either way: notifications/mic are best-effort. If mic is still
        // denied, RecordingService will just skip audio rather than crash, since
        // Prefs.mic being true with no permission is checked again by the OS at
        // MediaRecorder.prepare() time -- but we re-read the setting to decide
        // whether to warn the caller up front.
        val needsMic = Prefs.readAll(prefs).mic
        if (needsMic && getPermissionState("microphone") != PermissionState.GRANTED) {
            val ret = JSObject()
            ret.put("started", false)
            ret.put("error", "mic_permission_denied")
            call.resolve(ret)
            return
        }
        beginCapture(call)
    }

    private fun beginCapture(call: PluginCall) {
        saveCall(call)
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(call, mgr.createScreenCaptureIntent(), "handleCaptureResult")
    }

    @ActivityCallback
    private fun handleCaptureResult(call: PluginCall?, result: androidx.activity.result.ActivityResult) {
        if (call == null) return
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            val ret = JSObject()
            ret.put("started", false)
            ret.put("error", "permission_denied")
            call.resolve(ret)
            return
        }
        val settings = Prefs.readAll(prefs)
        val svcIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
            putExtra(RecordingService.EXTRA_FPS, settings.fps)
            putExtra(RecordingService.EXTRA_QUALITY, settings.quality)
            putExtra(RecordingService.EXTRA_STORAGE_MODE, settings.storageMode)
            putExtra(RecordingService.EXTRA_STORAGE_PATH, settings.storagePath)
            putExtra(RecordingService.EXTRA_MIC, settings.mic)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, svcIntent)
        val ret = JSObject()
        ret.put("started", true)
        call.resolve(ret)
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) {
        if (!RecordingService.isRunning) {
            val ret = JSObject()
            ret.put("stopped", false)
            call.resolve(ret)
            return
        }
        val svcIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(svcIntent)
        // Give the service a brief moment to finalize the file, then respond.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ret = JSObject()
            ret.put("stopped", true)
            ret.put("filePath", RecordingService.lastFilePath ?: "")
            call.resolve(ret)
        }, 600)
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        val ret = JSObject()
        ret.put("recording", RecordingService.isRunning)
        call.resolve(ret)
    }

    @PluginMethod
    fun getSettings(call: PluginCall) {
        val s = Prefs.readAll(prefs)
        val ret = JSObject()
        ret.put("fps", s.fps)
        ret.put("quality", s.quality)
        ret.put("storageMode", s.storageMode)
        ret.put("storagePath", s.storagePath)
        ret.put("theme", s.theme)
        ret.put("mic", s.mic)
        call.resolve(ret)
    }

    @PluginMethod
    fun setSettings(call: PluginCall) {
        val editor = prefs.edit()
        call.getData().keys().forEach { key ->
            when (key) {
                "fps" -> editor.putInt(Prefs.FPS, call.getInt("fps") ?: 30)
                "quality" -> editor.putString(Prefs.QUALITY, call.getString("quality") ?: "high")
                "storageMode" -> editor.putString(Prefs.STORAGE_MODE, call.getString("storageMode") ?: "default")
                "storagePath" -> editor.putString(Prefs.STORAGE_PATH, call.getString("storagePath") ?: "")
                "theme" -> editor.putString(Prefs.THEME, call.getString("theme") ?: "system")
                "mic" -> editor.putBoolean(Prefs.MIC, call.getBoolean("mic") ?: false)
            }
        }
        editor.apply()
        call.resolve()
    }

    @PluginMethod
    fun pickStorageFolder(call: PluginCall) {
        saveCall(call)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(call, intent, "handleFolderPick")
    }

    @ActivityCallback
    private fun handleFolderPick(call: PluginCall?, result: androidx.activity.result.ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("cancelled")
            return
        }
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val ret = JSObject()
        ret.put("uri", uri.toString())
        call.resolve(ret)
    }
}
