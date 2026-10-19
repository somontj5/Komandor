package com.komandor.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ScreenDriver (этап M1, каркас): AccessibilityService, который умеет
 * читать дерево экрана и выполнять базовые действия (клик, ввод, скролл).
 *
 * Соединение с MainActivity/AgentService будет добавлено на этапе M3
 * (сериализация экрана + Router). Пока — рабочий каркас с тестовой функцией.
 */
class ScreenDriver : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenDriver? = null
            private set

        val isEnabled: Boolean
            get() = instance != null

        /** Тестовая команда этапа M1: нажать кнопку на текущем экране. */
        fun performTestClick(): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val clickable = findFirstClickable(root) ?: return false
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        private fun findFirstClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isClickable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findFirstClickable(child)
                if (found != null) return found
            }
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Обработка событий экрана появится на этапе M3 (сериализация дерева).
    }

    override fun onInterrupt() {
        // Обязательный метод интерфейса; прерывание не требует действий.
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
