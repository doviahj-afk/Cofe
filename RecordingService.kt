package com.joshua.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.joshua.screenrecorder.START"
        const val ACTION_STOP = "com.joshua.screenrecorder.STOP"
        const val ACTION_STATE_CHANGED = "com.joshua.screenrecorder.STATE_CHANGED"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_STORAGE_MODE = "storageMode"
        const val EXTRA_STORAGE_PATH = "storagePath"
        const val EXTRA_MIC = "mic"

        private const val CHANNEL_ID = "screen_recorder_channel"
        private const val NOTIF_ID = 4201

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastFilePath: String? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var isMediaStoreOutput = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        createChannelIfNeeded()
        startForegroundCompat()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "high"
        val storageMode = intent.getStringExtra(EXTRA_STORAGE_MODE) ?: "default"
        val storagePath = intent.getStringExtra(EXTRA_STORAGE_PATH) ?: ""
        val mic = intent.getBooleanExtra(EXTRA_MIC, false)

        if (resultData == null) {
            broadcastState(false)
            stopForegroundCompat()
            stopSelf()
            return
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, resultData) ?: run {
            broadcastState(false)
            stopForegroundCompat()
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // System revoked capture (e.g. user tapped Stop in the system chip)
                finalizeAndStop()
            }
        }, null)

        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val bitrate = when (quality) {
            "low" -> 2_000_000
            "medium" -> 6_000_000
            else -> 12_000_000
        }

        val fileName = "ScreenRecord_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        try {
            val recorder = MediaRecorder()
            if (mic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            val fd = openOutputFd(storageMode, storagePath, fileName)
            recorder.setOutputFile(fd.fileDescriptor)

            if (mic) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoEncodingBitRate(bitrate)
            recorder.prepare()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder",
                width, height, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )

            recorder.start()
            mediaRecorder = recorder
            pfd = fd
            isRunning = true
            broadcastState(true)
        } catch (e: Exception) {
            e.printStackTrace()
            cleanUp()
            broadcastState(false)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun openOutputFd(storageMode: String, storagePath: String, fileName: String): ParcelFileDescriptor {
        return if (storageMode == "custom" && storagePath.isNotEmpty()) {
            isMediaStoreOutput = false
            val tree = DocumentFile.fromTreeUri(this, Uri.parse(storagePath))
                ?: throw IllegalStateException("Cannot open chosen folder")
            val doc = tree.createFile("video/mp4", fileName)
                ?: throw IllegalStateException("Cannot create file in chosen folder")
            outputUri = doc.uri
            contentResolver.openFileDescriptor(doc.uri, "w")
                ?: throw IllegalStateException("Cannot open file descriptor")
        } else {
            isMediaStoreOutput = true
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create MediaStore entry")
            outputUri = uri
            contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Cannot open file descriptor")
        }
    }

    private fun handleStop() {
        finalizeAndStop()
    }

    private fun finalizeAndStop() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (isMediaStoreOutput && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outputUri?.let {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(it, values, null, null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        lastFilePath = outputUri?.toString()
        cleanUp()
        broadcastState(false)
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanUp() {
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
        virtualDisplay = null
        mediaProjection = null
        mediaRecorder = null
        pfd = null
        isRunning = false
    }

    private fun broadcastState(recording: Boolean) {
        val intent = Intent(ACTION_STATE_CHANGED).putExtra("recording", recording)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Screen Recording", NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun startForegroundCompat() {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording screen")
            .setContentText("Tap to stop")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .setContentIntent(stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }
}
