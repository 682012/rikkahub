package me.rerere.rikkahub.data.organization

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

object ImageOrganizerWork {
    const val MANUAL = "image-organizer-manual"
    private const val AUTO = "image-organizer-auto"
    fun enqueue(context: Context, jobId: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("$MANUAL-$jobId", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ImageOrganizerWorker>().setInputData(workDataOf("job_id" to jobId)).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun schedule(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork(AUTO); return }
        manager.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ImageOrganizerWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("automatic" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build()).build())
    }
}

class ImageOrganizerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("image_organizer", "图片后台整理", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "image_organizer")
            .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("正在整理图片")
            .setContentText("可以离开页面；完成的分类会自动保存")
            .setOngoing(true).addAction(android.R.drawable.ic_delete, "停止本次任务",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(20720, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(20720, notification)
    }
    override suspend fun doWork(): Result = try {
        val automatic = inputData.getBoolean("automatic", false)
        // The periodic trigger only queues a one-shot batch; cancelling the batch never disables automation.
        if (!automatic) setForeground(getForegroundInfo())
        GlobalContext.get().get<ImageOrganizerController>().executeQueue(automatic, inputData.getString("job_id"))
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(workDataOf("error" to e.message?.take(300))) }
}
