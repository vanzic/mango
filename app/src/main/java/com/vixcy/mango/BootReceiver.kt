package com.vixcy.mango

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("mango_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("recording_active", false)
        if (!isActive) return

        val width    = prefs.getInt("video_width", 720)
        val height   = prefs.getInt("video_height", 1280)
        val bitrate  = prefs.getInt("video_bitrate", 2_000_000)
        val fps      = prefs.getInt("video_fps", 30)
        val chunkMs  = prefs.getLong("chunk_duration_ms", 600_000L)
        val aspectW  = prefs.getInt("aspect_w", 16)
        val aspectH  = prefs.getInt("aspect_h", 9)

        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra("width", width)
            putExtra("height", height)
            putExtra("bitrate", bitrate)
            putExtra("fps", fps)
            putExtra("chunk_ms", chunkMs)
            putExtra("aspect_w", aspectW)
            putExtra("aspect_h", aspectH)
        }
        context.startForegroundService(serviceIntent)
    }
}
