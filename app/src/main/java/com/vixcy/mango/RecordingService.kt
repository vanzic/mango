package com.vixcy.mango

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP  = "ACTION_STOP"
        private const val PREFS   = "mango_prefs"
        private const val CHANNEL = "mango_recording_channel"
        private const val NOTIF_ID = 1
    }

    // Camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    // Recorder
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null

    // Params
    private var width = 1280
    private var height = 720
    private var bitrate = 2_000_000
    private var fps = 30
    private var chunkMs = 600_000L

    // State
    private var isRecording = false
    private val chunkHandler = Handler(android.os.Looper.getMainLooper())
    private val chunkRunnable = Runnable { rotateChunk() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                width    = intent.getIntExtra("width", 1280)
                height   = intent.getIntExtra("height", 720)
                bitrate  = intent.getIntExtra("bitrate", 2_000_000)
                fps      = intent.getIntExtra("fps", 30)
                chunkMs  = intent.getLongExtra("chunk_ms", 600_000L)
                savePrefs(true)
                startForegroundNotification()
                scheduleWatchdog()
                startCameraAndRecord()
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_STICKY
    }

    // ── Foreground Notification ────────────────────────────────────────────────

    private fun startForegroundNotification(text: String = "Recording active") {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mango Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Mango")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Mango")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    // ── Watchdog ───────────────────────────────────────────────────────────────

    private fun scheduleWatchdog() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = watchdogIntent()
        am.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 5 * 60 * 1000,
            5 * 60 * 1000,
            pi
        )
    }

    private fun cancelWatchdog() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.cancel(watchdogIntent())
    }

    private fun watchdogIntent(): PendingIntent {
        val i = Intent(this, WatchdogReceiver::class.java).apply {
            action = "com.vixcy.mango.WATCHDOG"
        }
        return PendingIntent.getBroadcast(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Camera + Recording ─────────────────────────────────────────────────────

    private fun startCameraAndRecord() {
        cameraThread = HandlerThread("MangoCamera").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startNewChunk(uploadPrevious = false)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, cameraHandler)
    }

    private fun startNewChunk(uploadPrevious: Boolean) {
        if (uploadPrevious) {
            currentFile?.let { enqueueUpload(it) }
        }

        val file = createOutputFile()
        currentFile = file

        val recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(width, height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(bitrate)
            setOutputFile(file.absolutePath)
            prepare()
        }
        mediaRecorder = recorder

        val surface = recorder.surface
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!
                        .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        .apply { addTarget(surface) }
                        .build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                    recorder.start()
                    isRecording = true
                    scheduleNextChunk()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, cameraHandler
        )
    }

    private fun scheduleNextChunk() {
        chunkHandler.removeCallbacks(chunkRunnable)
        chunkHandler.postDelayed(chunkRunnable, chunkMs)
    }

    private fun rotateChunk() {
        if (!isRecording) return
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        startNewChunk(uploadPrevious = true)
    }

    private fun stopRecording() {
        isRecording = false
        chunkHandler.removeCallbacks(chunkRunnable)
        savePrefs(false)
        cancelWatchdog()

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        currentFile?.let { enqueueUpload(it) }
        currentFile = null

        cameraDevice?.close()
        cameraDevice = null
        if (::cameraThread.isInitialized) cameraThread.quitSafely()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Upload ─────────────────────────────────────────────────────────────────

    private fun enqueueUpload(file: File) {
        updateNotification("Uploading ${file.name}...")
        val data = Data.Builder()
            .putString("file_path", file.absolutePath)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun createOutputFile(): File {
        val dir = File(getExternalFilesDir(null), "MangoRecordings").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(dir, "chunk_$ts.mp4")
    }

    private fun savePrefs(active: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean("recording_active", active)
            putInt("video_width", width)
            putInt("video_height", height)
            putInt("video_bitrate", bitrate)
            putInt("video_fps", fps)
            putLong("chunk_duration_ms", chunkMs)
            apply()
        }
    }
}