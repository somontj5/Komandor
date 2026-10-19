package com.komandor.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.komandor.app.MainActivity

/**
 * AgentService (этап M1): foreground-сервис с постоянным уведомлением.
 * Держит цикл задач живым, когда пользователь сворачивает приложение.
 */
class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "komandor_agent"
        const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        private var instance: AgentService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Здесь позже начнётся цикл обработки задач (Router -> ScreenDriver).
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Агент Командор",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Командор")
            .setContentText("Агент активен и ждёт задач")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
