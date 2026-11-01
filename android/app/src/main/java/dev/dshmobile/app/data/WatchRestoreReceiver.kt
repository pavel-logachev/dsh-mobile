package dev.dshmobile.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** Normal boot (after credential unlock) and package upgrade only. No force-stop bypass. */
class WatchRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { resumePhoneWatches(context.applicationContext) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { NotificationPosting.logFailure(failure) }
            finally { pending.finish(); cancel() }
        }
    }
}
