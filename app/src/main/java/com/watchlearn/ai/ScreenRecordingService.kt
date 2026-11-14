package com.watchlearn.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records video from a user-approved MediaProjection session.
 *
 * This service intentionally records video only. Microphone or playback audio
 * requires separate permissions and additional Android capture rules.
 */
class ScreenRecordingService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var isRecording = false
    private var isStopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("WatchLearn AI is recording")
            .setContentText("Your approved task is being recorded.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_recording), stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = getResultData(intent)
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startRecording(resultCode, resultData)
        } catch (_: Exception) {
            releaseResources(deleteOutput = true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseResources(deleteOutput = isRecording)
        super.onDestroy()
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjection was not granted")

        val bounds: Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        } else {
            Rect(
                0,
                0,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
        }
        val width = bounds.width()
        val height = bounds.height()
        val density = resources.displayMetrics.densityDpi

        createOutput(width, height)
        val recorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outputPfd = contentResolver.openFileDescriptor(requireNotNull(outputUri), "w")
                    ?: error("Could not open recording output")
                setOutputFile(requireNotNull(outputPfd).fileDescriptor)
            } else {
                setOutputFile(requireNotNull(outputFile).absolutePath)
            }
            prepare()
        }
        mediaRecorder = recorder

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }.also { mediaProjection?.registerCallback(it, null) }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WatchLearn AI Screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            null
        )
        recorder.start()
        isRecording = true
    }

    private fun createOutput(width: Int, height: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "WatchLearn_AI_$stamp.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.WIDTH, width)
                put(MediaStore.Video.Media.HEIGHT, height)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/WatchLearn AI"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Could not create recording output")
        } else {
            val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "WatchLearn AI")
            directory.mkdirs()
            outputFile = File(directory, name)
        }
    }

    private fun stopRecording() {
        if (isStopping) return
        isStopping = true
        if (!isRecording && mediaRecorder == null) {
            stopSelf()
            return
        }

        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            releaseResources(deleteOutput = true)
            stopSelf()
            return
        }

        isRecording = false
        releaseResources(deleteOutput = false)
        stopSelf()
    }

    private fun releaseResources(deleteOutput: Boolean) {
        virtualDisplay?.release()
        virtualDisplay = null

        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null

        projectionCallback?.let { callback ->
            mediaProjection?.unregisterCallback(callback)
        }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
        outputPfd?.close()
        outputPfd = null

        outputUri?.let { uri ->
            if (deleteOutput) {
                contentResolver.delete(uri, null, null)
            } else {
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
        }
        if (deleteOutput) outputFile?.delete()
        outputUri = null
        outputFile = null
    }

    @Suppress("DEPRECATION")
    private fun getResultData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    companion object {
        const val ACTION_STOP = "com.watchlearn.ai.action.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "watchlearn_recording"
        private const val NOTIFICATION_ID = 1001
    }
}
