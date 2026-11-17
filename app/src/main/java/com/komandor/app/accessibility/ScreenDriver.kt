package com.komandor.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.komandor.app.security.Blocklist
import org.json.JSONArray
import org.json.JSONObject

/**
 * ScreenDriver (M3+ Production Engine):
 * Полноценный драйвер AccessibilityService для Командор — Claude Code Mobile.
 *
 * Возможности:
 * - Сериализация UI-дерева активного окна с индексами [1], [2], координатами, типами и состояниями
 * - Клик по индексу элемента, по тексту/описанию, по viewId или точным координатам (x, y)
 * - Ввод текста (ACTION_SET_TEXT) в поле по индексу, тексту или в текущее сфокусированное поле
 * - Скролл вверх/вниз/влево/вправо, свайп-жесты и долгое нажатие
 * - Системные глобальные действия (Назад, Домой, Недавние, Уведомления)
 * - Автоматический контроль безопасности через Blocklist (WhatsApp запрещён, детекция капч/2FA)
 */
class ScreenDriver : AccessibilityService() {

    data class IndexedUiNode(
        val index: Int,
        val className: String,
        val text: String,
        val contentDescription: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean,
        val focused: Boolean
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    data class ScreenSnapshot(
        val packageName: String,
        val timestampMs: Long,
        val nodes: List<IndexedUiNode>,
        val safetyVerdict: Blocklist.SafetyVerdict,
        val formattedTree: String
    )

    companion object {
        @Volatile
        var instance: ScreenDriver? = null
            private set

        @Volatile
        var lastSnapshot: ScreenSnapshot? = null
            private set

        val isEnabled: Boolean
            get() = instance != null

        /** Тестовая команда этапа M1: нажать первую доступную кнопку на текущем экране. */
        fun performTestClick(): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val pkg = root.packageName?.toString() ?: ""
            if (Blocklist.isBlocked(pkg)) return false
            val clickable = findFirstClickable(root) ?: return false
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        private fun findFirstClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isClickable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findFirstClickable(child)
                if (found != null) return found
            }
            return null
        }

        /**
         * Снимает полный слепок текущего экрана, индексирует интерактивные и текстовые узлы
         * и проверяет экран через систему безопасности Blocklist.
         */
        fun captureScreenSnapshot(): ScreenSnapshot {
            val service = instance
            if (service == null) {
                return ScreenSnapshot(
                    packageName = "com.komandor.app (Accessibility выключен)",
                    timestampMs = System.currentTimeMillis(),
                    nodes = emptyList(),
                    safetyVerdict = Blocklist.SafetyVerdict(
                        allowed = true,
                        reason = "AccessibilityService не активирован — работаем в локальном режиме песочницы."
                    ),
                    formattedTree = "[Системное уведомление]: Служба «Командор — драйвер экрана» ещё не включена в Настройках -> Специальные возможности. Локальные инструменты Claude Code (Bash, Read, Write, Edit, Glob, Grep) работают в полном режиме."
                )
            }

            val root = service.rootInActiveWindow
            if (root == null) {
                return ScreenSnapshot(
                    packageName = "unknown",
                    timestampMs = System.currentTimeMillis(),
                    nodes = emptyList(),
                    safetyVerdict = Blocklist.SafetyVerdict(true, "OK"),
                    formattedTree = "[Экран пуст или окно недоступно]"
                )
            }

            val pkg = root.packageName?.toString() ?: "unknown"
            val collected = mutableListOf<IndexedUiNode>()
            val rawTextBuilder = StringBuilder()

            fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 28) return
                if (!node.isVisibleToUser) return

                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
                val cls = node.className?.toString()?.substringAfterLast(".") ?: "View"

                if (text.isNotEmpty()) rawTextBuilder.append(text).append(' ')
                if (desc.isNotEmpty()) rawTextBuilder.append(desc).append(' ')

                val isInteresting = node.isClickable ||
                        node.isEditable ||
                        node.isScrollable ||
                        node.isCheckable ||
                        text.isNotEmpty() ||
                        desc.isNotEmpty()

                if (isInteresting && collected.size < 120) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        collected.add(
                            IndexedUiNode(
                                index = collected.size + 1,
                                className = cls,
                                text = text,
                                contentDescription = desc,
                                viewId = viewId,
                                bounds = rect,
                                clickable = node.isClickable,
                                editable = node.isEditable,
                                scrollable = node.isScrollable,
                                checked = node.isChecked,
                                focused = node.isFocused
                            )
                        )
                    }
                }

                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i), depth + 1)
                }
            }

            traverse(root, 0)
            val safety = Blocklist.inspectScreenSafety(pkg, rawTextBuilder.toString())

            val sb = StringBuilder()
            sb.appendLine("Активный пакет: $pkg")
            sb.appendLine("Безопасность: ${if (safety.allowed) "РАЗРЕШЕНО" else "ЗАБЛОКИРОВАНО (${safety.reason})"}")
            sb.appendLine("Элементов обнаружено: ${collected.size}")
            sb.appendLine("---")
            for (n in collected) {
                val flags = buildList {
                    if (n.clickable) add("CLICK")
                    if (n.editable) add("EDIT")
                    if (n.scrollable) add("SCROLL")
                    if (n.checked) add("CHECKED")
                    if (n.focused) add("FOCUSED")
                }.joinToString(",")
                val label = when {
                    n.text.isNotEmpty() && n.contentDescription.isNotEmpty() -> "\"${n.text}\" (${n.contentDescription})"
                    n.text.isNotEmpty() -> "\"${n.text}\""
                    n.contentDescription.isNotEmpty() -> "(${n.contentDescription})"
                    else -> "<без текста>"
                }
                val idPart = if (n.viewId.isNotEmpty()) " #$n.viewId" else ""
                sb.appendLine("[${n.index}] ${n.className}$idPart $label [$flags] @(${n.centerX},${n.centerY})")
            }

            val snapshot = ScreenSnapshot(
                packageName = pkg,
                timestampMs = System.currentTimeMillis(),
                nodes = collected,
                safetyVerdict = safety,
                formattedTree = sb.toString().trim()
            )
            lastSnapshot = snapshot
            return snapshot
        }

        /** Клик по индексу элемента из последнего снимка экрана. */
        fun clickByIndex(index: Int): String {
            val service = instance ?: return "Ошибка: AccessibilityService не включён."
            val snap = captureScreenSnapshot()
            if (!snap.safetyVerdict.allowed) return snap.safetyVerdict.reason
            val target = snap.nodes.find { it.index == index }
                ?: return "Элемент с индексом [$index] не найден на экране (всего: ${snap.nodes.size})."

            val root = service.rootInActiveWindow
            if (root != null) {
                val realNode = findMatchingNode(root, target)
                if (realNode != null && clickNodeOrParent(realNode)) {
                    return "Выполнен клик по элементу [$index] ${target.className} \"${target.text.ifEmpty { target.contentDescription }}\""
                }
            }

            val tapped = service.dispatchTapGesture(target.centerX.toFloat(), target.centerY.toFloat())
            return if (tapped) {
                "Выполнен жест нажатия по элементу [$index] в координатах (${target.centerX}, ${target.centerY})."
            } else {
                "Не удалось нажать элемент [$index]."
            }
        }

        /** Клик по тексту, contentDescription или viewId. */
        fun clickByText(query: String): String {
            val service = instance ?: return "Ошибка: AccessibilityService не включён."
            val snap = captureScreenSnapshot()
            if (!snap.safetyVerdict.allowed) return snap.safetyVerdict.reason

            val q = query.trim().lowercase()
            val match = snap.nodes.firstOrNull {
                it.text.lowercase().contains(q) ||
                        it.contentDescription.lowercase().contains(q) ||
                        it.viewId.lowercase().contains(q)
            } ?: return "Элемент с текстом «$query» не найден на текущем экране."

            return clickByIndex(match.index)
        }

        /** Клик по точным экранным координатам (x, y). */
        fun clickAtCoordinates(x: Int, y: Int): String {
            val service = instance ?: return "Ошибка: AccessibilityService не включён."
            val snap = captureScreenSnapshot()
            if (!snap.safetyVerdict.allowed) return snap.safetyVerdict.reason

            val ok = service.dispatchTapGesture(x.toFloat(), y.toFloat())
            return if (ok) "Нажатие по координатам ($x, $y) выполнено."
            else "Ошибка выполнения жеста нажатия в ($x, $y)."
        }

        /** Ввод текста в поле по индексу или в первое доступное поле ввода. */
        fun inputText(text: String, targetIndex: Int? = null): String {
            val service = instance ?: return "Ошибка: AccessibilityService не включён."
            val snap = captureScreenSnapshot()
            if (!snap.safetyVerdict.allowed) return snap.safetyVerdict.reason

            val root = service.rootInActiveWindow ?: return "Ошибка: активное окно недоступно."

            val nodeToFill: AccessibilityNodeInfo? = if (targetIndex != null) {
                val indexed = snap.nodes.find { it.index == targetIndex }
                if (indexed != null) findMatchingNode(root, indexed) else null
            } else {
                findFocusedOrFirstEditable(root)
            }

            if (nodeToFill == null) {
                return "Поле ввода (EditText) не найдено на экране."
            }

            nodeToFill.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            nodeToFill.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = nodeToFill.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return if (success) {
                "Текст «$text» успешно введён в поле."
            } else {
                "Не удалось установить текст через ACTION_SET_TEXT."
            }
        }

        /** Прокрутка экрана (up, down, left, right). */
        fun scrollScreen(direction: String): String {
            val service = instance ?: return "Ошибка: AccessibilityService не включён."
            val snap = captureScreenSnapshot()
            if (!snap.safetyVerdict.allowed) return snap.safetyVerdict.reason

            val root = service.rootInActiveWindow
            if (root != null) {
                val scrollable = findFirstScrollable(root)
                if (scrollable != null) {
                    val action = when (direction.lowercase()) {
                        "up", "вверх", "left", "влево" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    }
                    if (scrollable.performAction(action)) {
                        return "Прокрутка ($direction) выполнена через узел списка."
                    }
                }
            }

            val metrics = service.resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val cy = metrics.heightPixels / 2f
            val deltaY = metrics.heightPixels * 0.32f
            val deltaX = metrics.widthPixels * 0.32f

            val (sx, sy, ex, ey) = when (direction.lowercase()) {
                "up", "вверх" -> listOf(cx, cy - deltaY, cx, cy + deltaY)
                "left", "влево" -> listOf(cx + deltaX, cy, cx - deltaX, cy)
                "right", "вправо" -> listOf(cx - deltaX, cy, cx + deltaX, cy)
                else -> listOf(cx, cy + deltaY, cx, cy - deltaY) // down
            }
            val ok = service.dispatchSwipeGesture(sx, sy, ex, ey, 320L)
            return if (ok) "Жест прокрутки ($direction) выполнен." else "Не удалось выполнить прокрутку."
        }

        /** Системные клавиши: BACK, HOME, RECENTS, NOTIFICATIONS. */
        fun performSystemAction(actionName: String): String {
            val service = instance ?: return "Ошибка: AccessibilityService не включён."
            val globalAction = when (actionName.trim().lowercase()) {
                "back", "назад" -> GLOBAL_ACTION_BACK
                "home", "домой" -> GLOBAL_ACTION_HOME
                "recents", "недавние", "overview" -> GLOBAL_ACTION_RECENTS
                "notifications", "уведомления" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return "Неизвестное системное действие: $actionName"
            }
            val ok = service.performGlobalAction(globalAction)
            return if (ok) "Системное действие '$actionName' выполнено."
            else "Ошибка выполнения системного действия '$actionName'."
        }

        /** Сериализация последнего снимка экрана в JSON для LLM. */
        fun snapshotToJson(): JSONObject {
            val snap = captureScreenSnapshot()
            val arr = JSONArray()
            for (n in snap.nodes) {
                arr.put(
                    JSONObject().apply {
                        put("index", n.index)
                        put("class", n.className)
                        put("text", n.text)
                        put("desc", n.contentDescription)
                        put("id", n.viewId)
                        put("clickable", n.clickable)
                        put("editable", n.editable)
                        put("scrollable", n.scrollable)
                        put("x", n.centerX)
                        put("y", n.centerY)
                    }
                )
            }
            return JSONObject().apply {
                put("package", snap.packageName)
                put("safe", snap.safetyVerdict.allowed)
                put("safetyReason", snap.safetyVerdict.reason)
                put("nodeCount", snap.nodes.size)
                put("nodes", arr)
            }
        }

        private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 6) {
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = current.parent
                depth++
            }
            return false
        }

        private fun findMatchingNode(
            node: AccessibilityNodeInfo?,
            target: IndexedUiNode
        ): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (rect == target.bounds && text == target.text && desc == target.contentDescription) {
                return node
            }
            for (i in 0 until node.childCount) {
                val found = findMatchingNode(node.getChild(i), target)
                if (found != null) return found
            }
            return null
        }

        private fun findFocusedOrFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            if (node.isEditable && node.isFocused) return node
            var fallback: AccessibilityNodeInfo? = if (node.isEditable) node else null
            for (i in 0 until node.childCount) {
                val childResult = findFocusedOrFirstEditable(node.getChild(i))
                if (childResult != null) {
                    if (childResult.isFocused) return childResult
                    if (fallback == null) fallback = childResult
                }
            }
            return fallback
        }

        private fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val found = findFirstScrollable(node.getChild(i))
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
        val pkg = event?.packageName?.toString() ?: return
        if (Blocklist.isBlocked(pkg)) {
            // При попадании в запрещённый WhatsApp агент немедленно выходит назад
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onInterrupt() {
        // Обязательный метод интерфейса
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun dispatchTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
