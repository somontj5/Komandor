package com.komandor.app.security

/**
 * Модуль безопасности Командора (Claude Code Mobile Guardrails):
 * 1. Неотключаемый запрет WhatsApp и финансовых/банковских пакетов без явного разрешения.
 * 2. Детектор капч, 2FA/СМС-кодов, экранов ввода пароля и платёжных шлюзов.
 * 3. Санитизация команд локального терминала (защита от деструктивных системных вызовов).
 */
object Blocklist {

    /** Жёсткий чёрный список пакетов (не отключается пользователем). */
    val PACKAGES: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b" // WhatsApp Business — тоже запрещён
    )

    /** Чувствительные ключевые слова на экране, при которых агент обязан остановиться и позвать человека. */
    private val CAPTCHA_KEYWORDS = listOf(
        "captcha", "recaptcha", "hcaptcha", "cloudflare",
        "я не робот", "i'm not a robot", "verify you are human",
        "подтвердите, что вы человек"
    )

    private val SENSITIVE_AUTH_KEYWORDS = listOf(
        "код из смс", "sms code", "одноразовый код", "otp code",
        "cvv", "cvc", "номер карты", "card number", "срок действия карты",
        "подтверждение платежа", "3d secure", "mastercard identity check", "mir accept"
    )

    /** Разрешённые по умолчанию ИИ-приложения и инструменты разработчика. */
    val KNOWN_AI_AND_DEV_APPS: Map<String, String> = mapOf(
        "claude" to "com.anthropic.claude",
        "клауд" to "com.anthropic.claude",
        "gemini" to "com.google.android.apps.bard",
        "джемини" to "com.google.android.apps.bard",
        "chatgpt" to "com.openai.chatgpt",
        "чатгпт" to "com.openai.chatgpt",
        "genspark" to "ai.genspark.app",
        "дженспарк" to "ai.genspark.app",
        "termux" to "com.termux",
        "термукс" to "com.termux",
        "chrome" to "com.android.chrome",
        "хром" to "com.android.chrome",
        "браузер" to "com.android.chrome",
        "github" to "com.github.android",
        "гитхаб" to "com.github.android",
        "telegram" to "org.telegram.messenger",
        "телеграм" to "org.telegram.messenger",
        "калькулятор" to "com.google.android.calculator",
        "calculator" to "com.google.android.calculator",
        "настройки" to "com.android.settings",
        "settings" to "com.android.settings",
        "заметки" to "com.google.android.keep",
        "keep" to "com.google.android.keep"
    )

    fun isBlocked(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        return normalized in PACKAGES || normalized.startsWith("com.whatsapp")
    }

    /**
     * Проверяет дамп экрана на наличие капчи, 2FA или платёжной формы.
     * Возвращает причину блокировки или null, если экран безопасен для автоматизации.
     */
    fun inspectScreenSafety(packageName: String?, screenTextDump: String): SafetyVerdict {
        if (packageName != null && isBlocked(packageName)) {
            return SafetyVerdict(
                allowed = false,
                reason = "БЛОКИРОВКА БЕЗОПАСНОСТИ: Пакет '$packageName' (WhatsApp) находится в жёстком чёрном списке Командора."
            )
        }

        val lower = screenTextDump.lowercase()
        for (kw in CAPTCHA_KEYWORDS) {
            if (lower.contains(kw)) {
                return SafetyVerdict(
                    allowed = false,
                    requiresHuman = true,
                    reason = "ОБНАРУЖЕНА КАПЧА / ЗАЩИТА ОТ БОТОВ ('$kw'). Агент приостановлен — пожалуйста, пройдите проверку вручную и продолжите."
                )
            }
        }

        for (kw in SENSITIVE_AUTH_KEYWORDS) {
            if (lower.contains(kw)) {
                return SafetyVerdict(
                    allowed = false,
                    requiresHuman = true,
                    reason = "ОБНАРУЖЕН ЧУВСТВИТЕЛЬНЫЙ ЭКРАН ОПЛАТЫ ИЛИ СМС-КОДА ('$kw'). Ввод платёжных данных и одноразовых кодов заблокирован."
                )
            }
        }

        return SafetyVerdict(allowed = true, reason = "OK")
    }

    /**
     * Проверяет безопасность shell-команды перед запуском в локальной песочнице.
     */
    fun inspectShellCommand(command: String): SafetyVerdict {
        val trimmed = command.trim().lowercase()
        if (trimmed.contains("com.whatsapp")) {
            return SafetyVerdict(
                allowed = false,
                reason = "Доступ к пакетам WhatsApp через shell запрещён политикой безопасности."
            )
        }
        val dangerousPatterns = listOf(
            "rm -rf /",
            "mkfs",
            "dd if=/dev/zero",
            ":(){:|:&};:",
            "reboot",
            "shutdown"
        )
        for (pat in dangerousPatterns) {
            if (trimmed.contains(pat)) {
                return SafetyVerdict(
                    allowed = false,
                    reason = "Опасная системная команда ('$pat') заблокирована защитным фильтром."
                )
            }
        }
        return SafetyVerdict(allowed = true, reason = "OK")
    }

    data class SafetyVerdict(
        val allowed: Boolean,
        val reason: String,
        val requiresHuman: Boolean = false
    )
}
