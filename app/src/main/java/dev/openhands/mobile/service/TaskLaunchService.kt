package dev.openhands.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.openhands.mobile.MainActivity
import dev.openhands.mobile.R
import dev.openhands.mobile.data.remote.SessionHolder
import dev.openhands.mobile.data.repo.ConversationRepository
import dev.openhands.mobile.data.repo.StartOutcome
import dev.openhands.mobile.data.repo.StartStatus
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Carries a task launch to completion after the app leaves the foreground.
 *
 * Only the *start* of a task needs the phone: the agent itself runs in an OpenHands cloud
 * sandbox. Startup can take minutes (sandbox provisioning, repo clone, skill setup), and a
 * plain coroutine in a ViewModel dies when the process is killed on swipe. A short-lived
 * foreground service keeps the process alive for exactly that window, then stops.
 *
 * This intentionally does not attempt to run for the whole task. Android 15+ caps
 * `dataSync` services at 6h/24h, and a long-lived service would be both fragile and a
 * pointless battery drain given the work happens server-side.
 */
@AndroidEntryPoint
class TaskLaunchService : LifecycleService() {

    @Inject lateinit var repository: ConversationRepository
    @Inject lateinit var session: SessionHolder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val startTaskId = intent?.getStringExtra(EXTRA_START_TASK_ID)
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()

        if (startTaskId == null || session.tokenOrNull() == null) {
            // Without a start task or an unlocked session there is nothing to finish.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        promoteToForeground(getString(R.string.launch_notification_starting, title))

        lifecycleScope.launch {
            val outcome = runCatching {
                repository.awaitStart(startTaskId, onProgress = { status ->
                    updateNotification(progressText(status, title))
                })
            }.getOrElse { StartOutcome.Failed(it.message ?: "Unexpected failure") }

            notifyResult(outcome, title)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun progressText(status: StartStatus, title: String): String = when (status) {
        StartStatus.WAITING_FOR_SANDBOX -> getString(R.string.launch_status_sandbox)
        StartStatus.PREPARING_REPOSITORY -> getString(R.string.launch_status_repository)
        StartStatus.RUNNING_SETUP_SCRIPT -> getString(R.string.launch_status_setup)
        StartStatus.SETTING_UP_GIT_HOOKS -> getString(R.string.launch_status_hooks)
        StartStatus.SETTING_UP_SKILLS -> getString(R.string.launch_status_skills)
        StartStatus.STARTING_CONVERSATION -> getString(R.string.launch_status_conversation)
        StartStatus.READY -> getString(R.string.launch_status_ready)
        StartStatus.ERROR -> getString(R.string.launch_status_error)
        else -> getString(R.string.launch_notification_starting, title)
    }

    private fun notifyResult(outcome: StartOutcome, title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = when (outcome) {
            is StartOutcome.Ready -> buildNotification(
                text = getString(R.string.launch_result_running),
                ongoing = false,
                conversationId = outcome.conversationId,
            )

            is StartOutcome.Failed -> buildNotification(
                text = getString(R.string.launch_result_failed, outcome.detail),
                ongoing = false,
            )
        }
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun promoteToForeground(text: String) {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            ONGOING_NOTIFICATION_ID,
            buildNotification(text, ongoing = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_NOTIFICATION_ID, buildNotification(text, ongoing = true))
    }

    private fun buildNotification(
        text: String,
        ongoing: Boolean,
        conversationId: String? = null,
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            conversationId?.let { putExtra(MainActivity.EXTRA_CONVERSATION_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(ongoing)
            .setCategory(if (ongoing) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.launch_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.launch_channel_description) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "task_launch"
        private const val ONGOING_NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        private const val EXTRA_START_TASK_ID = "start_task_id"
        private const val EXTRA_TITLE = "title"

        fun launch(context: Context, startTaskId: String, title: String) {
            val intent = Intent(context, TaskLaunchService::class.java).apply {
                putExtra(EXTRA_START_TASK_ID, startTaskId)
                putExtra(EXTRA_TITLE, title)
            }
            context.startForegroundService(intent)
        }
    }
}
