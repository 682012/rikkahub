package me.rerere.rikkahub.data.organization

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import org.koin.core.context.GlobalContext

/** Private, job-specific notification action; never grants access to external apps. */
class ImageOrganizerStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val jobId = intent.getStringExtra("job_id") ?: return
        val pending = goAsync()
        val koin = GlobalContext.getOrNull()
        if (koin == null) { pending.finish(); return }
        try {
            koin.get<AppScope>().launch {
                try { koin.get<ImageOrganizerController>().cancelJob(jobId) }
                catch (e: Exception) { Log.e("ImageOrganizer", "Unable to persist stop", e) }
                finally { pending.finish() }
            }
        } catch (e: Exception) {
            pending.finish()
            Log.e("ImageOrganizer", "Unable to dispatch stop", e)
        }
    }

    companion object {
        private const val ACTION_STOP = "me.rerere.rikkahub.STOP_IMAGE_ORGANIZER"
        fun pendingIntent(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ImageOrganizerStopReceiver::class.java)
                .setAction(ACTION_STOP)
                .setData(Uri.Builder().scheme("image-organizer").authority("stop").appendPath(jobId).build())
                .putExtra("job_id", jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
