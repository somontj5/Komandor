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
 * AgentService (M1-M4 Production): foreground-сервис с живым статусом текущей задачи Claude Code.
 * Держит цикл задач живым, когда пользователь переключается в управляемое приложение (Claude, Gemini, браузер и т.д.).
 */
class AgentService : Service() {

    companion object {
        const val CHANNEL_ID = "komandor_agent"
        const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentStatusText: String = "Агент активен и ждёт задач"
            private set

        private var instance: AgentService? = null

        fun updateStatus(statusText: String) {
            currentStatusText = statusText
            val svc = instance ?: return
            val nm = svc.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, svc.buildNotification(statusText))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val customStatus = intent?.getStringExtra("status")
        if (!customStatus.isNullOrBlank()) {
            updateStatus(customStatus)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        currentStatusText = "Агент остановлен"
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Агент Командор (Claude Code Mobile)",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    internal fun buildNotification(statusText: String = currentStatusText): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Командор • Claude Code")
            .setContentText(statusText)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
