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

    @Suppress("MissingPermission")
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
                startNewChunk()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, cameraHandler)
    }

    @Suppress("NewApi")
    private fun startNewChunk() {
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

        // Snapshot the file reference before async work touches it
        val prevFile = currentFile

        // Tell the camera to stop producing frames. Each op in its own try-catch:
        // a single shared try block means a stopRepeating() throw skips stop(),
        // leaving the moov atom unwritten → 0-sec file.
        try { captureSession?.stopRepeating()  } catch (e: Exception) { e.printStackTrace() }
        try { captureSession?.abortCaptures()  } catch (e: Exception) { e.printStackTrace() }

        // Post recorder finalization to the camera thread so it runs AFTER
        // stopRepeating/abortCaptures have been processed by the camera HAL,
        // not racing with in-flight frame delivery to the encoder surface.
        cameraHandler.post {
            try { mediaRecorder?.stop()    } catch (e: Exception) { e.printStackTrace() }
            try { mediaRecorder?.release() } catch (e: Exception) { e.printStackTrace() }
            mediaRecorder = null

            prevFile?.let { enqueueUpload(it) }
            startNewChunk()
        }
    }

    private fun stopRecording() {
        isRecording = false
        chunkHandler.removeCallbacks(chunkRunnable)
        savePrefs(false)
        cancelWatchdog()

        // Snapshot before the camera thread touches currentFile
        val fileToUpload = currentFile
        currentFile = null

        if (!::cameraHandler.isInitialized) {
            // Camera was never opened (e.g. stop pressed before camera finished opening)
            fileToUpload?.let { enqueueUpload(it) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Step 1 (main thread): signal the camera to stop sending frames.
        // Isolated try-catch — a failure here must NOT prevent recorder finalization.
        try { captureSession?.stopRepeating() } catch (e: Exception) { e.printStackTrace() }

        // Step 2 (camera thread): finalize the recorder AFTER the camera HAL has
        // processed the stop command. Previously this ran on the main thread immediately
        // after posting stopRepeating, so stop() raced with in-flight frames → the
        // encoder surface received frames after stop() was called, which threw a
        // RuntimeException that the single shared catch block swallowed, leaving the
        // file with no moov atom → 0:00 on Telegram.
        cameraHandler.post {
            try { mediaRecorder?.stop()    } catch (e: Exception) { e.printStackTrace() }
            try { mediaRecorder?.release() } catch (e: Exception) { e.printStackTrace() }
            mediaRecorder = null

            try { captureSession?.close(); captureSession = null } catch (e: Exception) {}
            try { cameraDevice?.close();   cameraDevice   = null } catch (e: Exception) {}

            // Service teardown must happen on the main thread
            Handler(android.os.Looper.getMainLooper()).post {
                fileToUpload?.let { enqueueUpload(it) }
                cameraThread.quitSafely()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
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