package com.opencode.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OConnectorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OConnector Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps connection to server alive"
        }
        val completionChannel = NotificationChannel(
            CHANNEL_ID_COMPLETION,
            "AI Completion",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifies when an AI reply completes"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(completionChannel)
    }

    companion object {
        const val CHANNEL_ID = "sse_service_channel"
        const val CHANNEL_ID_COMPLETION = "ai_completion_channel"
    }
}
