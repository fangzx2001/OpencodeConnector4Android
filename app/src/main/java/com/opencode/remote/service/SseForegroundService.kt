package com.opencode.remote.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.opencode.remote.OConnectorApp
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.api.dto.ServerEvent
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class SseForegroundService : Service() {

    @Inject lateinit var sseClient: OConnectorSseClient
    @Inject lateinit var eventBus: SseEventBus
    @Inject lateinit var repository: OConnectorRepository
    @Inject @Named("applicationScope") lateinit var appScope: CoroutineScope

    private var sseJob: Job? = null

    /** Last notification time per session — debounces duplicate session.idle events. */
    private val lastIdleNotify = ConcurrentHashMap<String, Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val generation = if (intent == null) {
            // Android restarted the service (e.g., after memory kill).
            // Recover using the repository's current generation — this is
            // the same generation that was active when we died.
            val savedGen = repository.currentGeneration
            Log.w(TAG, "onStartCommand with null intent (service restart), recovering with generation=$savedGen")
            savedGen
        } else {
            intent.getLongExtra("generation", 0L)
        }

        // Activate generation on event bus — filters stale events at source
        eventBus.activateGeneration(generation)

        startSseCollection(generation)

        return START_STICKY
    }

    private fun startSseCollection(generation: Long) {
        // Cancel existing SSE collection if any (handles server switch)
        sseJob?.cancel()
        sseJob = appScope.launch {
            try {
                sseClient.subscribeToEvents().collect { event ->
                    eventBus.emit(event, generation)
                    onServerEvent(event)
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSE collection stopped: ${e.message}")
            }
        }
    }

    /**
     * Notify when an AI reply completes, regardless of which screen the user is on.
     * Uses the reliable session.idle event (server schema guarantees sessionID).
     */
    private fun onServerEvent(event: ServerEvent) {
        try {
            if (event.payload.type != "session.idle") return
            val sessionId = event.payload.properties.sessionID ?: return

            // Debounce duplicate idle events per session (e.g. SSE reconnect replay).
            val now = System.currentTimeMillis()
            val last = lastIdleNotify[sessionId]
            if (last != null && now - last < IDLE_NOTIFY_DEBOUNCE_MS) return
            lastIdleNotify[sessionId] = now

            notifyTurnCompleted(sessionId, event.directory ?: repository.activeSessionDirectory)
        } catch (e: Exception) {
            Log.w(TAG, "Notification tracking error: ${e.message}")
        }
    }

    private fun notifyTurnCompleted(sessionId: String, directory: String?) {
        appScope.launch {
            var dir = directory
            var title: String? = null
            var isSubAgent = false
            try {
                val session = repository.getSession(sessionId, dir)
                isSubAgent = !session.parentID.isNullOrBlank()
                if (isSubAgent) return@launch
                title = session.title ?: session.slug
                if (dir == null) dir = session.directory
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch session info for notification", e)
            }
            showTurnCompletedNotification(sessionId, dir, title)
        }
    }

    private fun showTurnCompletedNotification(sessionId: String, directory: String?, title: String?) {
        val strings = com.opencode.remote.ui.strings.AppLocale.strings
        val displayName = title
            ?: directory
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() }
            ?: sessionId.take(8)

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("sessionId", sessionId)
            putExtra("directory", directory)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, OConnectorApp.CHANNEL_ID_COMPLETION)
            .setContentTitle(strings.aiReplyComplete)
            .setContentText(strings.aiReplyCompleteDesc.format(displayName))
            .setSmallIcon(com.opencode.remote.R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(completionNotificationId(sessionId), notification)
        Log.d(TAG, "Turn completed notification sent: session=$sessionId dir=$directory title=$title")
    }

    private fun completionNotificationId(sessionId: String): Int {
        val hash = sessionId.hashCode()
        val bounded = if (hash == Int.MIN_VALUE) 0 else Math.abs(hash)
        return COMPLETION_NOTIFICATION_ID_BASE + (bounded % 1000)
    }

    override fun onDestroy() {
        sseJob?.cancel()
        sseJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            repository.activeSessionId?.let { putExtra("sessionId", it) }
            repository.activeSessionDirectory?.let { putExtra("directory", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, OConnectorApp.CHANNEL_ID)
            .setContentTitle("OConnector")
            .setContentText("OConnector is running")
            .setSmallIcon(com.opencode.remote.R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SseForegroundService"
        const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_ID_BASE = 2002
        private const val IDLE_NOTIFY_DEBOUNCE_MS = 10_000L

        @Volatile
        private var lastRestartTime: Long = 0L
        private const val RESTART_DEBOUNCE_MS = 3000L

        fun start(context: Context, generation: Long = 0L) {
            try {
                val intent = Intent(context, SseForegroundService::class.java)
                intent.putExtra("generation", generation)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SSE foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SseForegroundService::class.java)
            context.stopService(intent)
        }

        fun restart(context: Context, generation: Long = 0L) {
            synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - lastRestartTime < RESTART_DEBOUNCE_MS) {
                    Log.d(TAG, "Restart debounced (${now - lastRestartTime}ms since last)")
                    return
                }
                lastRestartTime = now
            }
            stop(context)
            try {
                start(context, generation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart SSE foreground service", e)
            }
        }
    }
}
