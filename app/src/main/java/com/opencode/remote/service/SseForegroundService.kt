package com.opencode.remote.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.opencode.remote.OConnectorApp
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class SseForegroundService : Service() {

    @Inject lateinit var sseClient: OConnectorSseClient
    @Inject lateinit var eventBus: SseEventBus
    @Inject @Named("applicationScope") lateinit var appScope: CoroutineScope

    private var sseJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (sseJob == null || sseJob?.isActive == false) {
            sseJob = appScope.launch {
                try {
                    sseClient.subscribeToEvents().collect { event ->
                        eventBus.emit(event)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE collection stopped: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        sseJob?.cancel()
        sseJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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

        fun start(context: Context) {
            val intent = Intent(context, SseForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SseForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
