package com.vixcy.mango

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UploadWorker"
        // Work tag observed by MainActivity to drive the upload status chip.
        // Every upload request (initial + retries) carries this tag so the UI
        // can aggregate all in-flight uploads into a single indicator.
        const val UPLOAD_TAG = "mango_upload"
    }

    override suspend fun doWork(): Result {
        val filePath = inputData.getString("file_path") ?: run {
            Log.e(TAG, "No file path provided")
            return Result.failure()
        }

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            return Result.failure()
        }

        val success = TelegramUploader.uploadVideo(file)

        return if (success) {
            file.delete()
            Log.d(TAG, "Deleted after upload: ${file.name}")
            Result.success()
        } else {
            if (runAttemptCount < 3) {
                Log.w(TAG, "Retrying upload: ${file.name} (attempt $runAttemptCount)")
                Result.retry()
            } else {
                Log.e(TAG, "Max retries reached, giving up: ${file.name}")
                Result.failure()
            }
        }
    }
}