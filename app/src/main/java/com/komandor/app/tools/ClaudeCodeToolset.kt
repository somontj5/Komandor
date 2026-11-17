package com.komandor.app.tools

import android.content.Context
import android.content.Intent
import com.komandor.app.accessibility.ScreenDriver
import com.komandor.app.security.Blocklist
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ClaudeCodeToolset — полный арсенал инструментов Claude Code в кармане на Android.
 *
 * Поддерживаемые инструменты (Tools):
 * 1. Bash — выполнение shell-команд внутри локального workspace на устройстве
 * 2. Read — чтение файла с нумерацией строк (`cat -n` формат)
 * 3. Write — создание или перезапись файла
 * 4. Edit — точечная замена (`old_string` -> `new_string`) с генерацией unified diff
 * 5. Glob — быстрый поиск файлов по шаблону в рабочем каталоге
 * 6. Grep — поиск по регулярному выражению внутри файлов
 * 7. TodoWrite — управление списком задач сессии (pending / in_progress / completed)
 * 8. ScreenInspect — снимок дерева элементов экрана через AccessibilityService
 * 9. ScreenAction — управление экраном (click_index, click_text, click_xy, type, scroll, back, home, recents)
 * 10. AppLaunch — запуск разрешённых приложений (Claude, Gemini, Genspark, Termux и др., кроме WhatsApp)
 */
class ClaudeCodeToolset(private val context: Context) {

    data class ToolExecutionResult(
        val toolName: String,
        val success: Boolean,
        val output: String,
        val diffPreview: String? = null,
        val durationMs: Long = 0L
    )

    data class TodoItem(
        val id: String,
        val content: String,
        val status: String, // "pending", "in_progress", "completed"
        val priority: String = "high"
    )

    val workspaceDir: File = File(context.filesDir, "workspace").apply {
        if (!exists()) {
            mkdirs()
            seedDefaultWorkspace(this)
        }
    }

    val todos = mutableListOf<TodoItem>(
        TodoItem("1", "Инициализировать рабочее окружение Командор Claude Code", "completed", "high"),
        TodoItem("2", "Проверить подключение ScreenDriver (Accessibility) и список инструментов", "in_progress", "high"),
        TodoItem("3", "Выполнить задачу пользователя в автономном цикле ReAct", "pending", "high")
    )

    private fun seedDefaultWorkspace(root: File) {
        try {
            File(root, "CLAUDE.md").writeText(
                """
                # CLAUDE.md — Инструкции агента Командор (Claude Code Mobile)

                ## О проекте
                Командор — автономный ИИ-агент и мобильная среда разработки в стиле Claude Code для Android.
                Агент совмещает:
                1. Файловые и терминальные инструменты (`Bash`, `Read`, `Write`, `Edit`, `Glob`, `Grep`, `TodoWrite`)
                2. Управление приложениями телефона через `AccessibilityService` (`ScreenInspect`, `ScreenAction`, `AppLaunch`)

                ## Правила безопасности
                - Никогда не открывать и не автоматизировать WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`).
                - При обнаружении капчи, 2FA/СМС-кода или экрана ввода банковской карты — немедленно остановиться и передать управление человеку.
                - Перед редактированием файла через `Edit` всегда читать его через `Read`.
                """.trimIndent()
            )

            val srcDir = File(root, "src").apply { mkdirs() }
            File(srcDir, "agent_demo.py").writeText(
                """
                #!/usr/bin/env python3
                # Демо-скрипт в локальном воркспейсе Командора

                def analyze_tasks(tasks):
                    completed = [t for t in tasks if t.get("done")]
                    return f"Выполнено {len(completed)} из {len(tasks)} задач"

                if __name__ == "__main__":
                    sample = [
                        {"title": "Проверить экран", "done": True},
                        {"title": "Сгенерировать патч", "done": True},
                        {"title": "Отправить отчёт", "done": False},
                    ]
                    print(analyze_tasks(sample))
                """.trimIndent()
            )

            val scriptsDir = File(root, "scripts").apply { mkdirs() }
            File(scriptsDir, "device_info.sh").writeText(
                """
                #!/system/bin/sh
                echo "=== Командор Claude Code Mobile Environment ==="
                echo "Рабочая папка: $(pwd)"
                echo "Архитектура: $(uname -m)"
                echo "Ядро: $(uname -r)"
                echo "Файлы проекта:"
                ls -la
                """.trimIndent()
            )

            File(root, "notes.md").writeText(
                """
                # Заметки разработчика
                - Команда `/help` показывает все слэш-команды терминала.
                - Команда `/screen` делает мгновенный снимок UI-дерева активного окна.
                - Команда `/ls` выводит дерево файлов воркспейса.
                """.trimIndent()
            )
        } catch (_: Exception) {
        }
    }

    fun resolveWorkspaceFile(relativeOrAbsPath: String): File {
        val cleaned = relativeOrAbsPath.trim().removePrefix("./").removePrefix("/")
        val candidate = if (relativeOrAbsPath.startsWith(workspaceDir.absolutePath)) {
            File(relativeOrAbsPath)
        } else {
            File(workspaceDir, cleaned)
        }
        val canonicalRoot = workspaceDir.canonicalPath
        val canonicalTarget = candidate.canonicalPath
        return if (canonicalTarget.startsWith(canonicalRoot)) {
            candidate
        } else {
            File(workspaceDir, candidate.name)
        }
    }

    /** Универсальная точка входа для вызова любого инструмента по имени и JSON-аргументам. */
    fun executeTool(name: String, args: JSONObject): ToolExecutionResult {
        val start = System.currentTimeMillis()
        val res = try {
            when (name.lowercase(Locale.ROOT)) {
                "bash", "shell" -> runBash(args.optString("command", ""))
                "read", "read_file" -> readFile(
                    path = args.optString("file_path", args.optString("path", "CLAUDE.md")),
                    offset = args.optInt("offset", 1),
                    limit = args.optInt("limit", 200)
                )
                "write", "write_file" -> writeFile(
                    path = args.optString("file_path", args.optString("path", "output.txt")),
                    content = args.optString("content", "")
                )
                "edit", "edit_file" -> editFile(
                    path = args.optString("file_path", args.optString("path", "")),
                    oldString = args.optString("old_string", ""),
                    newString = args.optString("new_string", ""),
                    replaceAll = args.optBoolean("replace_all", false)
                )
                "glob", "list_files" -> globFiles(args.optString("pattern", "*"))
                "grep", "search" -> grepFiles(
                    pattern = args.optString("pattern", ""),
                    include = args.optString("include", "")
                )
                "todowrite", "todo" -> updateTodos(args.optJSONArray("todos"))
                "screeninspect", "screen_inspect" -> inspectScreen()
                "screenaction", "screen_action" -> performScreenAction(args)
                "applaunch", "app_launch" -> launchApp(args.optString("app", args.optString("package", "")))
                else -> ToolExecutionResult(
                    toolName = name,
                    success = false,
                    output = "Неизвестный инструмент '$name'. Доступные: Bash, Read, Write, Edit, Glob, Grep, TodoWrite, ScreenInspect, ScreenAction, AppLaunch."
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = name,
                success = false,
                output = "Ошибка выполнения $name: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        return res.copy(durationMs = System.currentTimeMillis() - start)
    }

    fun runBash(command: String): ToolExecutionResult {
        if (command.isBlank()) {
            return ToolExecutionResult("Bash", false, "Пустая команда.")
        }
        val safety = Blocklist.inspectShellCommand(command)
        if (!safety.allowed) {
            return ToolExecutionResult("Bash", false, safety.reason)
        }

        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workspaceDir)
                .redirectErrorStream(true)
            val process = pb.start()
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ToolExecutionResult("Bash", false, "Таймаут выполнения команды (12 сек).")
            }
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            val code = process.exitValue()
            ToolExecutionResult(
                toolName = "Bash",
                success = code == 0,
                output = if (output.isEmpty()) "(exit code $code, без вывода)" else output.take(4000)
            )
        } catch (e: Exception) {
            // Fallback если /system/bin/sh недоступен в конкретной среде
            ToolExecutionResult("Bash", false, "Ошибка запуска shell: ${e.message}")
        }
    }

    fun readFile(path: String, offset: Int = 1, limit: Int = 200): ToolExecutionResult {
        val file = resolveWorkspaceFile(path)
        if (!file.exists() || !file.isFile) {
            return ToolExecutionResult("Read", false, "Файл не найден: ${file.relativeTo(workspaceDir).path}")
        }
        val lines = file.readLines()
        val startIdx = (offset - 1).coerceAtLeast(0)
        val endIdx = (startIdx + limit).coerceAtMost(lines.size)
        val numbered = buildString {
            appendLine("// Файл: ${file.relativeTo(workspaceDir).path} (${lines.size} строк)")
            for (i in startIdx until endIdx) {
                val lineNum = (i + 1).toString().padStart(4, ' ')
                appendLine("$lineNum | ${lines[i]}")
            }
        }.trimEnd()
        return ToolExecutionResult("Read", true, numbered)
    }

    fun writeFile(path: String, content: String): ToolExecutionResult {
        val file = resolveWorkspaceFile(path)
        file.parentFile?.mkdirs()
        val existed = file.exists()
        val oldContent = if (existed) file.readText() else ""
        file.writeText(content)
        val relPath = file.relativeTo(workspaceDir).path
        val diff = buildUnifiedDiff(relPath, oldContent, content)
        return ToolExecutionResult(
            toolName = "Write",
            success = true,
            output = "${if (existed) "Обновлён" else "Создан"} файл $relPath (${content.lines().size} строк, ${content.length} байт)",
            diffPreview = diff
        )
    }

    fun editFile(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean = false
    ): ToolExecutionResult {
        val file = resolveWorkspaceFile(path)
        if (!file.exists() || !file.isFile) {
            return ToolExecutionResult("Edit", false, "Файл не существует: $path")
        }
        if (oldString.isEmpty()) {
            return ToolExecutionResult("Edit", false, "Параметр old_string не может быть пустым.")
        }
        val original = file.readText()
        if (!original.contains(oldString)) {
            return ToolExecutionResult(
                "Edit",
                false,
                "Строка old_string не найдена в файле ${file.relativeTo(workspaceDir).path}. Сначала прочитайте актуальное содержимое через Read."
            )
        }
        val updated = if (replaceAll) {
            original.replace(oldString, newString)
        } else {
            original.replaceFirst(oldString, newString)
        }
        file.writeText(updated)
        val relPath = file.relativeTo(workspaceDir).path
        val diff = buildUnifiedDiff(relPath, original, updated)
        return ToolExecutionResult(
            toolName = "Edit",
            success = true,
            output = "Патч успешно применён к файлу $relPath.",
            diffPreview = diff
        )
    }

    fun globFiles(pattern: String): ToolExecutionResult {
        val regexPattern = pattern.trim()
            .replace(".", "\\.")
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .let { if (it.isEmpty() || it == "[^/]*") ".*" else ".*$it.*" }
        val regex = Regex(regexPattern, RegexOption.IGNORE_CASE)

        val matched = workspaceDir.walkTopDown()
            .filter { it.isFile }
            .map {
                val rel = it.relativeTo(workspaceDir).path
                val size = it.length()
                rel to size
            }
            .filter { (rel, _) -> regex.matches(rel) }
            .toList()

        val out = if (matched.isEmpty()) {
            "Файлы по шаблону '$pattern' не найдены."
        } else {
            buildString {
                appendLine("Найдено файлов (${matched.size}) в ${workspaceDir.absolutePath}:")
                for ((rel, size) in matched) {
                    appendLine("  • $rel ($size B)")
                }
            }.trimEnd()
        }
        return ToolExecutionResult("Glob", true, out)
    }

    fun grepFiles(pattern: String, include: String = ""): ToolExecutionResult {
        if (pattern.isBlank()) {
            return ToolExecutionResult("Grep", false, "Пустой поисковый паттерн.")
        }
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
        }

        val matches = mutableListOf<String>()
        workspaceDir.walkTopDown()
            .filter { it.isFile && it.length() < 512_000 }
            .forEach { file ->
                val rel = file.relativeTo(workspaceDir).path
                if (include.isNotBlank() && !rel.endsWith(include.removePrefix("*"))) return@forEach
                file.readLines().forEachIndexed { idx, line ->
                    if (regex.containsMatchIn(line) && matches.size < 80) {
                        matches.add("$rel:${idx + 1}: ${line.trim()}")
                    }
                }
            }

        val out = if (matches.isEmpty()) {
            "Совпадений по запросу '$pattern' не найдено."
        } else {
            "Найдено совпадений (${matches.size}):\n" + matches.joinToString("\n")
        }
        return ToolExecutionResult("Grep", true, out)
    }

    fun updateTodos(todosArray: JSONArray?): ToolExecutionResult {
        if (todosArray != null && todosArray.length() > 0) {
            todos.clear()
            for (i in 0 until todosArray.length()) {
                val item = todosArray.optJSONObject(i) ?: continue
                todos.add(
                    TodoItem(
                        id = item.optString("id", (i + 1).toString()),
                        content = item.optString("content", "Задача ${i + 1}"),
                        status = item.optString("status", "pending"),
                        priority = item.optString("priority", "high")
                    )
                )
            }
        }
        val formatted = formatTodoList()
        return ToolExecutionResult("TodoWrite", true, formatted)
    }

    fun formatTodoList(): String {
        if (todos.isEmpty()) return "Список задач пуст."
        return buildString {
            appendLine("План выполнения (Claude Code Todo List):")
            for (t in todos) {
                val icon = when (t.status) {
                    "completed" -> "[✓]"
                    "in_progress" -> "[●]"
                    else -> "[ ]"
                }
                appendLine("$icon #${t.id} ${t.content} (${t.status})")
            }
        }.trimEnd()
    }

    fun inspectScreen(): ToolExecutionResult {
        val snap = ScreenDriver.captureScreenSnapshot()
        return ToolExecutionResult(
            toolName = "ScreenInspect",
            success = snap.safetyVerdict.allowed,
            output = snap.formattedTree
        )
    }

    fun performScreenAction(args: JSONObject): ToolExecutionResult {
        val action = args.optString("action", "inspect").lowercase(Locale.ROOT)
        val resultText = when (action) {
            "click_index", "click" -> {
                if (args.has("index")) {
                    ScreenDriver.clickByIndex(args.optInt("index", 1))
                } else if (args.has("text")) {
                    ScreenDriver.clickByText(args.optString("text", ""))
                } else {
                    val ok = ScreenDriver.performTestClick()
                    if (ok) "Нажата первая кликабельная кнопка на экране."
                    else "Не удалось выполнить клик: проверьте, что AccessibilityService включён."
                }
            }
            "click_text" -> ScreenDriver.clickByText(args.optString("text", ""))
            "click_xy", "tap" -> ScreenDriver.clickAtCoordinates(
                args.optInt("x", 500),
                args.optInt("y", 500)
            )
            "type", "input" -> {
                val idx = if (args.has("index")) args.optInt("index") else null
                ScreenDriver.inputText(args.optString("text", ""), idx)
            }
            "scroll", "swipe" -> ScreenDriver.scrollScreen(args.optString("direction", "down"))
            "back", "home", "recents", "notifications" -> ScreenDriver.performSystemAction(action)
            else -> ScreenDriver.captureScreenSnapshot().formattedTree
        }
        val ok = !resultText.startsWith("Ошибка") && !resultText.startsWith("БЛОКИРОВКА") && !resultText.startsWith("ОБНАРУЖЕН")
        return ToolExecutionResult("ScreenAction($action)", ok, resultText)
    }

    fun launchApp(appNameOrPackage: String): ToolExecutionResult {
        val query = appNameOrPackage.trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("AppLaunch", false, "Не указано имя приложения или пакет.")
        }

        val resolvedPkg = Blocklist.KNOWN_AI_AND_DEV_APPS[query.lowercase(Locale.ROOT)] ?: query
        if (Blocklist.isBlocked(resolvedPkg) || query.lowercase(Locale.ROOT).contains("whatsapp") || query.lowercase(Locale.ROOT).contains("ватсап")) {
            return ToolExecutionResult(
                toolName = "AppLaunch",
                success = false,
                output = "БЛОКИРОВКА БЕЗОПАСНОСТИ: Запуск WhatsApp ($resolvedPkg) жёстко запрещён на уровне ядра Командора."
            )
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(resolvedPkg)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ToolExecutionResult("AppLaunch", true, "Приложение '$query' ($resolvedPkg) успешно запущено.")
        } else {
            ToolExecutionResult(
                "AppLaunch",
                false,
                "Пакет '$resolvedPkg' не установлен на этом устройстве. Проверьте имя пакета или установите приложение."
            )
        }
    }

    private fun buildUnifiedDiff(fileName: String, oldText: String, newText: String): String {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val newLines = if (newText.isEmpty()) emptyList() else newText.lines()
        val sb = StringBuilder()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("--- a/$fileName")
        sb.appendLine("+++ b/$fileName  ($ts)")

        val maxLines = maxOf(oldLines.size, newLines.size)
        var shownChanges = 0
        for (i in 0 until maxLines) {
            val o = oldLines.getOrNull(i)
            val n = newLines.getOrNull(i)
            if (o != n) {
                if (o != null) sb.appendLine("- ${i + 1}: $o")
                if (n != null) sb.appendLine("+ ${i + 1}: $n")
                shownChanges++
                if (shownChanges >= 24) {
                    sb.appendLine("... (показаны первые 24 изменённые строки)")
                    break
                }
            }
        }
        if (shownChanges == 0) {
            sb.appendLine("@@ Без изменений содержимого @@")
        }
        return sb.toString().trimEnd()
    }
}
