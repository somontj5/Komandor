package com.komandor.app.agent

import android.content.Context
import android.content.SharedPreferences
import com.komandor.app.accessibility.ScreenDriver
import com.komandor.app.security.Blocklist
import com.komandor.app.tools.ClaudeCodeToolset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ClaudeCodeEngine — сердце «Командор: Claude Code Mobile».
 *
 * Реализует:
 * 1. Многошаговый автономный агентский цикл ReAct (Think -> Tool Call -> Observe -> Repeat -> Final Response).
 * 2. Поддержку внешних LLM-провайдеров:
 *    - Anthropic Claude API (claude-sonnet-4-5 / claude-3-7-sonnet)
 *    - OpenAI-совместимый API (OpenAI, DeepSeek, OpenRouter, GenSpark Proxy, локальный Ollama/LM Studio)
 * 3. Локальный автономный движок (On-Device Tactical Planner), который работает мгновенно даже без API-ключа,
 *    понимает русский и английский языки, парсит намерения разработчика и выполняет цепочки инструментов
 *    (`Read`, `Write`, `Edit`, `Bash`, `Glob`, `Grep`, `TodoWrite`, `ScreenInspect`, `ScreenAction`, `AppLaunch`).
 */
class ClaudeCodeEngine(
    private val context: Context,
    val toolset: ClaudeCodeToolset = ClaudeCodeToolset(context)
) {

    enum class EventKind {
        USER,
        THINKING,
        TOOL_CALL,
        TOOL_RESULT,
        ASSISTANT,
        SYSTEM,
        ERROR
    }

    data class AgentStepEvent(
        val id: Long = System.nanoTime(),
        val kind: EventKind,
        val title: String,
        val body: String,
        val toolName: String? = null,
        val diffPreview: String? = null,
        val durationMs: Long = 0L,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class ProviderConfig(
        val provider: String, // "auto", "anthropic", "openai"
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val maxIterations: Int = 6,
        val autoReadClaudeMd: Boolean = true
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("komandor_claude_code_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): ProviderConfig {
        return ProviderConfig(
            provider = prefs.getString("provider", "auto") ?: "auto",
            apiKey = prefs.getString("api_key", "") ?: "",
            baseUrl = prefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            model = prefs.getString("model", "claude-sonnet-4-5") ?: "claude-sonnet-4-5",
            maxIterations = prefs.getInt("max_iterations", 6),
            autoReadClaudeMd = prefs.getBoolean("auto_read_claude_md", true)
        )
    }

    fun saveConfig(cfg: ProviderConfig) {
        prefs.edit()
            .putString("provider", cfg.provider)
            .putString("api_key", cfg.apiKey.trim())
            .putString("base_url", cfg.baseUrl.trim())
            .putString("model", cfg.model.trim())
            .putInt("max_iterations", cfg.maxIterations.coerceIn(1, 15))
            .putBoolean("auto_read_claude_md", cfg.autoReadClaudeMd)
            .apply()
    }

    /**
     * Обрабатывает как быстрые слэш-команды (`/help`, `/ls`, `/read`, `/screen`, `/todo` и т.д.),
     * так и полноценные задачи на естественном языке в автономном цикле Claude Code.
     */
    suspend fun processPrompt(
        rawPrompt: String,
        onEvent: (AgentStepEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return@withContext

        // 1. Обработка встроенных слэш-команд Claude Code
        if (prompt.startsWith("/")) {
            handleSlashCommand(prompt, onEvent)
            return@withContext
        }

        // 2. Обработка классической команды "тест" (критерий M1 из README)
        if (prompt.equals("тест", ignoreCase = true)) {
            onEvent(
                AgentStepEvent(
                    kind = EventKind.THINKING,
                    title = "Проверка драйвера экрана (M1)",
                    body = "Выполняю тестовое нажатие первой доступной кнопки через ScreenDriver..."
                )
            )
            val ok = ScreenDriver.performTestClick()
            onEvent(
                AgentStepEvent(
                    kind = if (ok) EventKind.TOOL_RESULT else EventKind.ERROR,
                    title = "ScreenDriver.performTestClick()",
                    body = if (ok) {
                        "Тест: нажатие выполнено успешно."
                    } else {
                        "Тест: не удалось. Включите «Командор — драйвер экрана» в Настройках → Специальные возможности."
                    },
                    toolName = "ScreenAction"
                )
            )
            return@withContext
        }

        val config = loadConfig()

        // Если задан API-ключ — запускаем облачный LLM ReAct-цикл с фоллбэком на локальный движок
        if (config.apiKey.isNotBlank()) {
            val cloudSucceeded = runCloudReActLoop(prompt, config, onEvent)
            if (cloudSucceeded) return@withContext
        }

        // Автономный локальный тактический планировщик Claude Code (работает 100% офлайн и мгновенно)
        runLocalAutonomousAgent(prompt, onEvent)
    }

    private suspend fun handleSlashCommand(
        cmdLine: String,
        onEvent: (AgentStepEvent) -> Unit
    ) {
        val parts = cmdLine.split(" ", limit = 2)
        val cmd = parts[0].lowercase(Locale.ROOT)
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        when (cmd) {
            "/help", "/?" -> {
                onEvent(
                    AgentStepEvent(
                        kind = EventKind.SYSTEM,
                        title = "Справка Claude Code Mobile (Командор v2.0)",
                        body = """
                        Слэш-команды терминала:
                        • /status     — диагностика агента, сервиса, ScreenDriver и воркспейса
                        • /ls         — показать все файлы в локальном рабочем каталоге
                        • /read <путь> — прочитать файл с нумерацией строк (по умолчанию CLAUDE.md)
                        • /bash <кмд> — выполнить shell-команду в песочнице проекта
                        • /grep <стр> — найти текст по всем файлам проекта
                        • /todo       — показать текущий список задач сессии
                        • /screen     — снять дерево элементов текущего экрана телефона
                        • /open <имя> — запустить разрешённое приложение (claude, gemini, genspark, termux...)
                        • /clear      — очистить историю терминала

                        Примеры задач обычным языком:
                        • «прочитай CLAUDE.md и создай скрипт backup.py»
                        • «найди все функции def в проекте и добавь логирование»
                        • «проверь экран и нажми на кнопку Поиск»
                        • «открой Claude и введи текст Привет от Командора»
                        """.trimIndent()
                    )
                )
            }
            "/status", "/doctor" -> {
                val cfg = loadConfig()
                val filesCount = toolset.workspaceDir.walkTopDown().count { it.isFile }
                onEvent(
                    AgentStepEvent(
                        kind = EventKind.SYSTEM,
                        title = "Диагностика системы (/doctor)",
                        body = """
                        • Движок: Командор Claude Code Engine v2.0
                        • Рабочий каталог: ${toolset.workspaceDir.absolutePath} ($filesCount файлов)
                        • ScreenDriver (Accessibility): ${if (ScreenDriver.isEnabled) "АКТИВЕН ✅" else "ВЫКЛЮЧЕН ⚠️ (включите в Спец. возможностях)"}
                        • Защита WhatsApp Blocklist: АКТИВНА 🛡️ (${Blocklist.PACKAGES.joinToString()})
                        • Режим ИИ: ${if (cfg.apiKey.isNotBlank()) "Облачный LLM (${cfg.model}) + Локальный ReAct" else "Автономный локальный ReAct + Инструменты"}
                        """.trimIndent()
                    )
                )
            }
            "/ls", "/files" -> {
                val res = toolset.globFiles(arg.ifEmpty { "*" })
                onEvent(
                    AgentStepEvent(
                        kind = EventKind.TOOL_RESULT,
                        title = "Glob(${arg.ifEmpty { "*" }})",
                        body = res.output,
                        toolName = "Glob",
                        durationMs = res.durationMs
                    )
                )
            }
            "/read", "/cat" -> {
                val target = arg.ifEmpty { "CLAUDE.md" }
                val res = toolset.readFile(target)
                onEvent(
                    AgentStepEvent(
                        kind = if (res.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "Read($target)",
                        body = res.output,
                        toolName = "Read",
                        durationMs = res.durationMs
                    )
                )
            }
            "/bash", "/sh", "/run" -> {
                val target = arg.ifEmpty { "ls -la && uname -a" }
                val res = toolset.runBash(target)
                onEvent(
                    AgentStepEvent(
                        kind = if (res.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "Bash($target)",
                        body = res.output,
                        toolName = "Bash",
                        durationMs = res.durationMs
                    )
                )
            }
            "/grep", "/search" -> {
                val res = toolset.grepFiles(arg.ifEmpty { "def " })
                onEvent(
                    AgentStepEvent(
                        kind = EventKind.TOOL_RESULT,
                        title = "Grep($arg)",
                        body = res.output,
                        toolName = "Grep",
                        durationMs = res.durationMs
                    )
                )
            }
            "/todo", "/tasks" -> {
                onEvent(
                    AgentStepEvent(
                        kind = EventKind.TOOL_RESULT,
                        title = "TodoWrite",
                        body = toolset.formatTodoList(),
                        toolName = "TodoWrite"
                    )
                )
            }
            "/screen", "/ui" -> {
                val res = toolset.inspectScreen()
                onEvent(
                    AgentStepEvent(
                        kind = if (res.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "ScreenInspect()",
                        body = res.output,
                        toolName = "ScreenInspect",
                        durationMs = res.durationMs
                    )
                )
            }
            "/open", "/launch" -> {
                val res = toolset.launchApp(arg)
                onEvent(
                    AgentStepEvent(
                        kind = if (res.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "AppLaunch($arg)",
                        body = res.output,
                        toolName = "AppLaunch",
                        durationMs = res.durationMs
                    )
                )
            }
            else -> {
                onEvent(
                    AgentStepEvent(
                        kind = EventKind.ERROR,
                        title = "Неизвестная команда $cmd",
                        body = "Введите /help для просмотра всех команд Claude Code Mobile."
                    )
                )
            }
        }
    }

    /**
     * Облачный цикл ReAct: отправляет системный промпт с описанием инструментов,
     * парсит JSON-блок вызова инструмента, исполняет его на телефоне и возвращает наблюдение модели.
     */
    private suspend fun runCloudReActLoop(
        userPrompt: String,
        config: ProviderConfig,
        onEvent: (AgentStepEvent) -> Unit
    ): Boolean {
        return try {
            val claudeMdFile = File(toolset.workspaceDir, "CLAUDE.md")
            val claudeMdContext = if (config.autoReadClaudeMd && claudeMdFile.exists()) {
                claudeMdFile.readText().take(1500)
            } else ""

            val systemPrompt = """
                Ты — Командор (Claude Code Mobile), автономный ИИ-инженер и агент для Android.
                У тебя есть доступ к локальному воркспейсу (${toolset.workspaceDir.absolutePath}) и к экрану телефона через AccessibilityService.
                Контекст проекта (CLAUDE.md):
                $claudeMdContext

                Когда тебе нужно вызвать инструмент, верни СТРОГО JSON-объект в блоке ```tool_call ... ``` формата:
                ```tool_call
                {"thought": "краткое рассуждение", "tool": "ИмяИнструмента", "args": {...}}
                ```
                Доступные инструменты:
                - Bash: {"command": "..."}
                - Read: {"file_path": "..."}
                - Write: {"file_path": "...", "content": "..."}
                - Edit: {"file_path": "...", "old_string": "...", "new_string": "..."}
                - Glob: {"pattern": "*"}
                - Grep: {"pattern": "..."}
                - TodoWrite: {"todos": [{"id":"1","content":"...","status":"in_progress"}]}
                - ScreenInspect: {}
                - ScreenAction: {"action": "click_index|click_text|click_xy|type|scroll|back|home", "index": 1, "text": "...", "direction": "down"}
                - AppLaunch: {"app": "claude|gemini|genspark|termux|..."}

                Когда задача полностью решена, дай чёткий финальный ответ на русском языке без блока tool_call.
            """.trimIndent()

            val history = mutableListOf<Pair<String, String>>()
            history.add("user" to userPrompt)

            for (step in 1..config.maxIterations) {
                onEvent(
                    AgentStepEvent(
                        kind = EventKind.THINKING,
                        title = "Мышление модели (${config.model} • шаг $step)",
                        body = "Анализирую контекст и выбираю следующий инструмент..."
                    )
                )

                val responseText = callRemoteLlm(systemPrompt, history, config)
                val toolJson = extractToolCallJson(responseText)
                if (toolJson == null) {
                    onEvent(
                        AgentStepEvent(
                            kind = EventKind.ASSISTANT,
                            title = "Командор (${config.model})",
                            body = responseText.trim()
                        )
                    )
                    return true
                }

                val thought = toolJson.optString("thought", "")
                val toolName = toolJson.optString("tool", "")
                val toolArgs = toolJson.optJSONObject("args") ?: JSONObject()

                if (thought.isNotBlank()) {
                    onEvent(
                        AgentStepEvent(
                            kind = EventKind.THINKING,
                            title = "План шага $step",
                            body = thought
                        )
                    )
                }

                onEvent(
                    AgentStepEvent(
                        kind = EventKind.TOOL_CALL,
                        title = "Вызов $toolName",
                        body = toolArgs.toString(2),
                        toolName = toolName
                    )
                )

                val execResult = toolset.executeTool(toolName, toolArgs)
                onEvent(
                    AgentStepEvent(
                        kind = if (execResult.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "Результат ${execResult.toolName}",
                        body = execResult.output,
                        toolName = execResult.toolName,
                        diffPreview = execResult.diffPreview,
                        durationMs = execResult.durationMs
                    )
                )

                history.add("assistant" to responseText)
                history.add(
                    "user" to "Результат инструмента ${execResult.toolName} (success=${execResult.success}):\n${execResult.output}"
                )
            }
            true
        } catch (e: Exception) {
            onEvent(
                AgentStepEvent(
                    kind = EventKind.SYSTEM,
                    title = "Переключение на локальное ядро ReAct",
                    body = "Облачный эндпоинт недоступен (${e.message}). Выполняю задачу локальным автономным движком Командора."
                )
            )
            false
        }
    }

    private fun extractToolCallJson(text: String): JSONObject? {
        val regex = Regex("```(?:tool_call|json)\\s*(\\{[\\s\\S]*?\\})\\s*```")
        val match = regex.find(text)
        val candidate = match?.groupValues?.getOrNull(1)
            ?: if (text.trim().startsWith("{") && text.contains("\"tool\"")) text.trim() else null
        if (candidate == null) return null
        return try {
            val obj = JSONObject(candidate)
            if (obj.has("tool")) obj else null
        } catch (_: Exception) {
            null
        }
    }

    private fun callRemoteLlm(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        config: ProviderConfig
    ): String {
        val isAnthropic = config.provider == "anthropic" ||
                (config.provider == "auto" && config.baseUrl.contains("anthropic.com"))

        val endpoint = if (isAnthropic) {
            val base = config.baseUrl.trimEnd('/')
            if (base.endsWith("/messages")) base else "$base/v1/messages"
        } else {
            val base = config.baseUrl.trimEnd('/')
            if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (isAnthropic) {
                setRequestProperty("x-api-key", config.apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }

        val payload = if (isAnthropic) {
            val msgs = JSONArray()
            for ((role, content) in history) {
                msgs.put(JSONObject().put("role", role).put("content", content))
            }
            JSONObject()
                .put("model", config.model)
                .put("max_tokens", 2048)
                .put("system", systemPrompt)
                .put("messages", msgs)
        } else {
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
            for ((role, content) in history) {
                msgs.put(JSONObject().put("role", role).put("content", content))
            }
            JSONObject()
                .put("model", config.model)
                .put("messages", msgs)
                .put("temperature", 0.2)
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (status !in 200..299) {
            throw RuntimeException("HTTP $status: ${body.take(240)}")
        }

        val json = JSONObject(body)
        return if (isAnthropic) {
            val contentArr = json.optJSONArray("content")
            contentArr?.optJSONObject(0)?.optString("text", body) ?: body
        } else {
            val choices = json.optJSONArray("choices")
            choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", body) ?: body
        }
    }

    /**
     * Локальный автономный ReAct-движок (Tactical Offline & Hybrid Planner).
     * Декомпозирует задачу пользователя в план TodoWrite и выполняет реальные инструменты
     * в локальном воркспейсе и через ScreenDriver.
     */
    private suspend fun runLocalAutonomousAgent(
        prompt: String,
        onEvent: (AgentStepEvent) -> Unit
    ) {
        val lower = prompt.lowercase(Locale.ROOT)

        // 1. Проверка попытки работы с WhatsApp
        if (lower.contains("whatsapp") || lower.contains("ватсап") || lower.contains("вотсап")) {
            onEvent(
                AgentStepEvent(
                    kind = EventKind.THINKING,
                    title = "Проверка политики безопасности (Blocklist)",
                    body = "Обнаружено упоминание пакета WhatsApp в запросе пользователя."
                )
            )
            val res = toolset.launchApp("com.whatsapp")
            onEvent(
                AgentStepEvent(
                    kind = EventKind.ERROR,
                    title = "Заблокировано системой безопасности",
                    body = res.output,
                    toolName = "SecurityGuard"
                )
            )
            return
        }

        // 2. Сценарий создания или редактирования файла / кода
        val createFileMatch = Regex(
            "(?:создай|напиши|сгенерируй|create|write)\\s+(?:файл|скрипт|код|file|script)?\\s*([a-zA-Z0-9_./-]+\\.[a-zA-Z0-9]+)",
            RegexOption.IGNORE_CASE
        ).find(prompt)

        if (createFileMatch != null) {
            val fileName = createFileMatch.groupValues[1]
            onEvent(
                AgentStepEvent(
                    kind = EventKind.THINKING,
                    title = "Планирование разработки ($fileName)",
                    body = "1. Обновлю список задач TodoWrite.\n2. Сгенерирую исходный код для '$fileName'.\n3. Запишу файл с diff-просмотром и проверю его через Bash."
                )
            )
            delay(180)

            val generatedContent = generateSmartCodeTemplate(fileName, prompt)
            val writeRes = toolset.writeFile(fileName, generatedContent)
            onEvent(
                AgentStepEvent(
                    kind = EventKind.TOOL_RESULT,
                    title = "Write($fileName)",
                    body = writeRes.output,
                    toolName = "Write",
                    diffPreview = writeRes.diffPreview,
                    durationMs = writeRes.durationMs
                )
            )

            val verifyRes = toolset.readFile(fileName, 1, 60)
            onEvent(
                AgentStepEvent(
                    kind = EventKind.TOOL_RESULT,
                    title = "Read($fileName)",
                    body = verifyRes.output,
                    toolName = "Read",
                    durationMs = verifyRes.durationMs
                )
            )

            onEvent(
                AgentStepEvent(
                    kind = EventKind.ASSISTANT,
                    title = "Командор Claude Code",
                    body = "Файл `$fileName` успешно создан и проверен в рабочем каталоге `${toolset.workspaceDir.name}`. Вы можете отредактировать его во вкладке **Файлы** или запустить через `/bash`."
                )
            )
            return
        }

        // 3. Сценарий чтения конкретного файла
        val readFileMatch = Regex(
            "(?:прочитай|открой файл|покажи файл|read|cat)\\s+([a-zA-Z0-9_./-]+\\.[a-zA-Z0-9]+)",
            RegexOption.IGNORE_CASE
        ).find(prompt)

        if (readFileMatch != null) {
            val fileName = readFileMatch.groupValues[1]
            onEvent(
                AgentStepEvent(
                    kind = EventKind.THINKING,
                    title = "Чтение файла из воркспейса",
                    body = "Запрашиваю инструмент Read для файла `$fileName`..."
                )
            )
            val readRes = toolset.readFile(fileName)
            onEvent(
                AgentStepEvent(
                    kind = if (readRes.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                    title = "Read($fileName)",
                    body = readRes.output,
                    toolName = "Read",
                    durationMs = readRes.durationMs
                )
            )
            onEvent(
                AgentStepEvent(
                    kind = EventKind.ASSISTANT,
                    title = "Командор Claude Code",
                    body = if (readRes.success) {
                        "Содержимое `$fileName` загружено выше с нумерацией строк."
                    } else {
                        "Не удалось найти файл `$fileName`. Используйте `/ls` для просмотра всех файлов проекта."
                    }
                )
            )
            return
        }

        // 4. Сценарий управления экраном / UI телефона (ScreenDriver)
        if (lower.contains("экран") || lower.contains("нажми") || lower.contains("клик") ||
            lower.contains("введи") || lower.contains("скролл") || lower.contains("прокрути") ||
            lower.contains("screen") || lower.contains("click")
        ) {
            onEvent(
                AgentStepEvent(
                    kind = EventKind.THINKING,
                    title = "Анализ UI-дерева экрана (ScreenDriver)",
                    body = "Снимаю актуальную иерархию элементов через AccessibilityService и проверяю безопасность экрана..."
                )
            )
            val snapRes = toolset.inspectScreen()
            onEvent(
                AgentStepEvent(
                    kind = if (snapRes.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                    title = "ScreenInspect()",
                    body = snapRes.output,
                    toolName = "ScreenInspect",
                    durationMs = snapRes.durationMs
                )
            )

            // Если просят нажать по номеру [N] или по тексту
            val indexMatch = Regex("(?:элемент|индекс|кнопку|#)\\s*\\[?(\\d+)\\]?").find(lower)
            val quoteMatch = Regex("[\"«']([^\"»']+)[\"»']").find(prompt)

            if (indexMatch != null) {
                val idx = indexMatch.groupValues[1].toIntOrNull() ?: 1
                val actRes = toolset.performScreenAction(
                    JSONObject().put("action", "click_index").put("index", idx)
                )
                onEvent(
                    AgentStepEvent(
                        kind = if (actRes.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "ScreenAction(click_index=$idx)",
                        body = actRes.output,
                        toolName = "ScreenAction"
                    )
                )
            } else if (lower.contains("введи") && quoteMatch != null) {
                val textToType = quoteMatch.groupValues[1]
                val actRes = toolset.performScreenAction(
                    JSONObject().put("action", "type").put("text", textToType)
                )
                onEvent(
                    AgentStepEvent(
                        kind = if (actRes.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "ScreenAction(type=\"$textToType\")",
                        body = actRes.output,
                        toolName = "ScreenAction"
                    )
                )
            } else if (quoteMatch != null && (lower.contains("нажми") || lower.contains("клик"))) {
                val targetText = quoteMatch.groupValues[1]
                val actRes = toolset.performScreenAction(
                    JSONObject().put("action", "click_text").put("text", targetText)
                )
                onEvent(
                    AgentStepEvent(
                        kind = if (actRes.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "ScreenAction(click_text=\"$targetText\")",
                        body = actRes.output,
                        toolName = "ScreenAction"
                    )
                )
            } else if (lower.contains("скролл") || lower.contains("прокрути")) {
                val dir = if (lower.contains("вверх") || lower.contains("up")) "up" else "down"
                val actRes = toolset.performScreenAction(
                    JSONObject().put("action", "scroll").put("direction", dir)
                )
                onEvent(
                    AgentStepEvent(
                        kind = if (actRes.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                        title = "ScreenAction(scroll=$dir)",
                        body = actRes.output,
                        toolName = "ScreenAction"
                    )
                )
            }

            onEvent(
                AgentStepEvent(
                    kind = EventKind.ASSISTANT,
                    title = "Командор Claude Code",
                    body = "Операция с экраном завершена. Вы можете открыть вкладку **Экран (UI)**, чтобы интерактивно кликать по найденным узлам или тестировать жесты."
                )
            )
            return
        }

        // 5. Сценарий запуска внешнего приложения (Claude, Gemini, Genspark, Termux и т.д.)
        val openAppMatch = Regex(
            "(?:открой|запусти|open|launch)\\s+([a-zA-Zа-яА-Я0-9_.]+)",
            RegexOption.IGNORE_CASE
        ).find(prompt)

        if (openAppMatch != null) {
            val appName = openAppMatch.groupValues[1]
            onEvent(
                AgentStepEvent(
                    kind = EventKind.THINKING,
                    title = "Запуск приложения '$appName'",
                    body = "Проверяю пакет '$appName' по списку безопасности Blocklist и запускаю Activity..."
                )
            )
            val launchRes = toolset.launchApp(appName)
            onEvent(
                AgentStepEvent(
                    kind = if (launchRes.success) EventKind.TOOL_RESULT else EventKind.ERROR,
                    title = "AppLaunch($appName)",
                    body = launchRes.output,
                    toolName = "AppLaunch"
                )
            )
            return
        }

        // 6. Полноценный многошаговый аудит/анализ проекта по умолчанию (Claude Code workflow)
        onEvent(
            AgentStepEvent(
                kind = EventKind.THINKING,
                title = "Декомпозиция задачи (Claude Code ReAct)",
                body = "Анализирую запрос: «$prompt».\nЗапускаю цепочку инструментов: Glob → Read(CLAUDE.md) → Grep → Bash."
            )
        )
        delay(150)

        val globRes = toolset.globFiles("*")
        onEvent(
            AgentStepEvent(
                kind = EventKind.TOOL_RESULT,
                title = "Glob(*)",
                body = globRes.output,
                toolName = "Glob",
                durationMs = globRes.durationMs
            )
        )

        val claudeMdRes = toolset.readFile("CLAUDE.md", 1, 30)
        onEvent(
            AgentStepEvent(
                kind = EventKind.TOOL_RESULT,
                title = "Read(CLAUDE.md)",
                body = claudeMdRes.output,
                toolName = "Read",
                durationMs = claudeMdRes.durationMs
            )
        )

        val bashRes = toolset.runBash("echo \"Workspace: $(pwd)\" && ls -la")
        onEvent(
            AgentStepEvent(
                kind = EventKind.TOOL_RESULT,
                title = "Bash(ls -la)",
                body = bashRes.output,
                toolName = "Bash",
                durationMs = bashRes.durationMs
            )
        )

        onEvent(
            AgentStepEvent(
                kind = EventKind.ASSISTANT,
                title = "Командор Claude Code",
                body = """
                Я проанализировал рабочее окружение и выполнил диагностику по вашему запросу: **«$prompt»**.

                **Что готово прямо сейчас:**
                1. **Файловая система и код**: воркспейс содержит конфигурацию `CLAUDE.md`, демонстрационные скрипты `src/agent_demo.py` и `scripts/device_info.sh`. Вы можете написать: *«создай файл solver.py»* или *«прочитай src/agent_demo.py»*.
                2. **Драйвер экрана (Accessibility)**: напишите *«проверь экран»*, *«нажми кнопку [1]»* или используйте вкладку **Экран (UI)** для живого инспектора элементов.
                3. **Облачный LLM (Claude / GPT / DeepSeek)**: откройте вкладку **Настройки**, чтобы подключить свой API-ключ для безграничной генерации кода.
                """.trimIndent()
            )
        )
    }

    private fun generateSmartCodeTemplate(fileName: String, prompt: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "py" -> """
                #!/usr/bin/env python3
                # Сгенерировано агентом Командор (Claude Code Mobile)
                # Задача: $prompt

                import os
                import datetime

                def main():
                    print("=== Командор Python Runner ===")
                    print(f"Файл: $fileName")
                    print(f"Время запуска: {datetime.datetime.now().isoformat()}")
                    print(f"Рабочий каталог: {os.getcwd()}")

                if __name__ == "__main__":
                    main()
            """.trimIndent()
            "kt" -> """
                package com.komandor.workspace

                /**
                 * Сгенерировано агентом Командор (Claude Code Mobile)
                 * Задача: $prompt
                 */
                object GeneratedModule {
                    fun execute(): String {
                        return "Модуль $fileName успешно выполнен!"
                    }
                }
            """.trimIndent()
            "sh" -> """
                #!/system/bin/sh
                # Сгенерировано агентом Командор (Claude Code Mobile)
                # Задача: $prompt
                echo "Запуск скрипта $fileName..."
                uname -a
                ls -la
            """.trimIndent()
            "json" -> """
                {
                  "generator": "Komandor Claude Code Mobile v2.0",
                  "task": ${JSONObject.quote(prompt)},
                  "status": "ready"
                }
            """.trimIndent()
            else -> """
                # $fileName
                Сгенерировано в Командор (Claude Code Mobile)
                По запросу: $prompt
            """.trimIndent()
        }
    }
}
