package com.komandor.app.security

/**
 * Чёрный список приложений (этап M2): пакеты, которые агенту запрещено
 * открывать и управлять ими. Запрет не отключается пользователем.
 */
object Blocklist {
    /** Пакеты WhatsApp (включая варианты для разных сборок). */
    val PACKAGES: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b" // WhatsApp Business — тоже запрещён
    )

    fun isBlocked(packageName: String): Boolean = packageName in PACKAGES
}
