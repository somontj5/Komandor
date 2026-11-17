package com.komandor.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.komandor.app.accessibility.ScreenDriver
import com.komandor.app.agent.ClaudeCodeEngine
import com.komandor.app.agent.ClaudeCodeEngine.AgentStepEvent
import com.komandor.app.agent.ClaudeCodeEngine.EventKind
import com.komandor.app.security.Blocklist
import com.komandor.app.service.AgentService
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * MainActivity — полноценная мобильная среда «Командор • Claude Code Mobile v2.0».
 *
 * 4 рабочие вкладки:
 * 1. Терминал (Claude Code CLI & ReAct Agent): поток карточек Thinking / ToolCall / Diff / Output,
 *    быстрые чипы слэш-команд (`/status`, `/ls`, `/read`, `/screen`, `/todo`, `тест`).
 * 2. Файлы (Workspace IDE): проводник локального воркспейса, создание/редактирование кода с подсветкой diff.
 * 3. Экран (Live UI Inspector): интерактивное дерево элементов AccessibilityService, клик по узлам,
 *    быстрые жесты (Скролл, Назад, Домой, Тест-клик) и проверка безопасности Blocklist.
 * 4. Настройки (LLM Router & Guardrails): подключение Claude / OpenAI / DeepSeek / Custom API.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = ClaudeCodeEngine(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D1117),
                    surface = Color(0xFF161B22),
                    primary = Color(0xFFD97757), // Фирменный терракотово-янтарный акцент Claude Code
                    secondary = Color(0xFF58A6FF),
                    tertiary = Color(0xFF7EE787)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0D1117)
                ) {
                    KomandorClaudeCodeApp(engine)
                }
            }
        }
    }
}

enum class WorkspaceTab(val title: String) {
    TERMINAL("Терминал"),
    FILES("Файлы"),
    SCREEN("Экран UI"),
    SETTINGS("Настройки")
}

@Composable
fun KomandorClaudeCodeApp(engine: ClaudeCodeEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(WorkspaceTab.TERMINAL) }
    var serviceRunning by remember { mutableStateOf(AgentService.isRunning) }
    var accessibilityEnabled by remember { mutableStateOf(ScreenDriver.isEnabled) }
    var isBusy by remember { mutableStateOf(false) }

    val events = remember {
        mutableStateListOf(
            AgentStepEvent(
                kind = EventKind.SYSTEM,
                title = "✻ Командор • Claude Code Mobile v2.0",
                body = "Рабочая среда инициализирована: ${engine.toolset.workspaceDir.absolutePath}\n" +
                        "Доступны инструменты: Bash, Read, Write, Edit, Glob, Grep, TodoWrite, ScreenInspect, ScreenAction, AppLaunch.\n" +
                        "Введите задачу обычным языком или выберите быструю команду ниже."
            )
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Верхний статус-бар в стиле Claude Code CLI
        TopStatusBar(
            serviceRunning = serviceRunning,
            accessibilityEnabled = accessibilityEnabled,
            isBusy = isBusy,
            onToggleService = {
                accessibilityEnabled = ScreenDriver.isEnabled
                if (serviceRunning) {
                    context.stopService(Intent(context, AgentService::class.java))
                    serviceRunning = false
                    events.add(
                        AgentStepEvent(
                            kind = EventKind.SYSTEM,
                            title = "AgentService",
                            body = "Фоновый сервис агента остановлен."
                        )
                    )
                } else {
                    context.startForegroundService(Intent(context, AgentService::class.java))
                    serviceRunning = true
                    events.add(
                        AgentStepEvent(
                            kind = EventKind.SYSTEM,
                            title = "AgentService",
                            body = "Фоновый сервис запущен. Уведомление активно в шторке Android."
                        )
                    )
                }
            },
            onOpenAccessibilitySettings = {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            }
        )

        // Панель переключения вкладок
        TabRow(
            selectedTabIndex = currentTab.ordinal,
            containerColor = Color(0xFF161B22),
            contentColor = Color(0xFFD97757)
        ) {
            WorkspaceTab.values().forEach { tab ->
                Tab(
                    selected = currentTab == tab,
                    onClick = {
                        accessibilityEnabled = ScreenDriver.isEnabled
                        currentTab = tab
                    },
                    text = {
                        Text(
                            text = tab.title,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = if (currentTab == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Контент активной вкладки
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (currentTab) {
                WorkspaceTab.TERMINAL -> TerminalTabView(
                    engine = engine,
                    events = events,
                    isBusy = isBusy,
                    onSubmitPrompt = { userText ->
                        val trimmed = userText.trim()
                        if (trimmed.equals("/clear", ignoreCase = true)) {
                            events.clear()
                            events.add(
                                AgentStepEvent(
                                    kind = EventKind.SYSTEM,
                                    title = "Терминал очищен",
                                    body = "Контекст сессии готов к новой задаче."
                                )
                            )
                            return@TerminalTabView
                        }
                        events.add(
                            AgentStepEvent(
                                kind = EventKind.USER,
                                title = "Вы",
                                body = trimmed
                            )
                        )
                        isBusy = true
                        AgentService.updateStatus("Выполняю: ${trimmed.take(36)}")
                        scope.launch {
                            try {
                                engine.processPrompt(trimmed) { stepEvent ->
                                    scope.launch {
                                        events.add(stepEvent)
                                    }
                                }
                            } finally {
                                isBusy = false
                                accessibilityEnabled = ScreenDriver.isEnabled
                                AgentService.updateStatus("Агент активен и ждёт задач")
                            }
                        }
                    }
                )

                WorkspaceTab.FILES -> FilesIdeTabView(
                    engine = engine,
                    onLogEvent = { ev -> events.add(ev) }
                )

                WorkspaceTab.SCREEN -> ScreenInspectorTabView(
                    engine = engine,
                    onOpenAccessibilitySettings = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                        }
                    }
                )

                WorkspaceTab.SETTINGS -> SettingsTabView(engine = engine)
            }
        }
    }
}

@Composable
private fun TopStatusBar(
    serviceRunning: Boolean,
    accessibilityEnabled: Boolean,
    isBusy: Boolean,
    onToggleService: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✻ КОМАНДОР",
                    color = Color(0xFFD97757),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isBusy) "● THINKING..." else "● CLAUDE CODE",
                    color = if (isBusy) Color(0xFFE3B341) else Color(0xFF7EE787),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (serviceRunning) "FGS: АКТИВЕН" else "FGS: СТОП",
                    color = if (serviceRunning) Color(0xFF7EE787) else Color(0xFF8B949E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = " • ",
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp
                )
                Text(
                    text = if (accessibilityEnabled) "ЭКРАН: ПОДКЛЮЧЕН" else "ЭКРАН: ВЫКЛ (нажмите)",
                    color = if (accessibilityEnabled) Color(0xFF58A6FF) else Color(0xFFFF7B72),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onOpenAccessibilitySettings() }
                )
            }
        }

        Button(
            onClick = onToggleService,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (serviceRunning) Color(0xFFDA3633) else Color(0xFF238636)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (serviceRunning) "Стоп" else "Старт",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color.White
            )
        }
    }
}

@Composable
private fun TerminalTabView(
    engine: ClaudeCodeEngine,
    events: List<AgentStepEvent>,
    isBusy: Boolean,
    onSubmitPrompt: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    val quickCommands = listOf(
        "/status",
        "/ls",
        "/read CLAUDE.md",
        "/todo",
        "/screen",
        "тест",
        "создай файл solver.py",
        "/bash ls -la"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Быстрые чипы команд в стиле Claude Code
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1117))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickCommands.forEach { cmd ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1F242C))
                        .border(1.dp, Color(0xFF30363D), RoundedCornerShape(6.dp))
                        .clickable(enabled = !isBusy) { onSubmitPrompt(cmd) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = cmd,
                        color = Color(0xFF58A6FF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Лента карточек терминала
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(events, key = { it.id }) { ev ->
                TerminalEventCard(ev)
            }
        }

        // Нижняя строка ввода Claude Code
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "❯",
                color = Color(0xFFD97757),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 6.dp)
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Задача или /команда (/help, /ls, тест)...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFF8B949E)
                    )
                },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                enabled = !isBusy && input.isNotBlank(),
                onClick = {
                    val toSend = input
                    input = ""
                    onSubmitPrompt(toSend)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97757))
            ) {
                Text("➤", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TerminalEventCard(event: AgentStepEvent) {
    val (borderColor, badgeColor, prefix) = when (event.kind) {
        EventKind.USER -> Triple(Color(0xFF238636), Color(0xFF7EE787), "❯ ВЫ")
        EventKind.THINKING -> Triple(Color(0xFF9E6A03), Color(0xFFE3B341), "✻ THINK")
        EventKind.TOOL_CALL -> Triple(Color(0xFF1F6FEB), Color(0xFF58A6FF), "⚙ TOOL")
        EventKind.TOOL_RESULT -> Triple(Color(0xFF30363D), Color(0xFF79C0FF), "✓ RESULT")
        EventKind.ASSISTANT -> Triple(Color(0xFFD97757), Color(0xFFFFA657), "✻ CLAUDE CODE")
        EventKind.SYSTEM -> Triple(Color(0xFF30363D), Color(0xFF8B949E), "ℹ SYSTEM")
        EventKind.ERROR -> Triple(Color(0xFFDA3633), Color(0xFFFF7B72), "✖ ERROR")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$prefix • ${event.title}",
                color = badgeColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            if (event.durationMs > 0) {
                Text(
                    text = "${event.durationMs} ms",
                    color = Color(0xFF8B949E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = event.body,
            color = Color(0xFFC9D1D9),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        if (!event.diffPreview.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0D1117))
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                event.diffPreview.lines().forEach { line ->
                    val lineColor = when {
                        line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF7EE787)
                        line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF7B72)
                        line.startsWith("---") || line.startsWith("+++") -> Color(0xFF58A6FF)
                        else -> Color(0xFF8B949E)
                    }
                    Text(
                        text = line,
                        color = lineColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FilesIdeTabView(
    engine: ClaudeCodeEngine,
    onLogEvent: (AgentStepEvent) -> Unit
) {
    var refreshTick by remember { mutableStateOf(0) }
    val files = remember(refreshTick) {
        engine.toolset.workspaceDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(engine.toolset.workspaceDir).path }
            .toList()
    }

    var selectedFile by remember { mutableStateOf<File?>(files.firstOrNull()) }
    var editorText by remember(selectedFile) {
        mutableStateOf(selectedFile?.takeIf { it.exists() }?.readText() ?: "")
    }
    var newFileName by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf("Выберите файл для редактирования или создайте новый.") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Воркспейс: ${engine.toolset.workspaceDir.absolutePath}",
            color = Color(0xFF8B949E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        // Горизонтальная лента файлов проекта
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            files.forEach { file ->
                val rel = file.relativeTo(engine.toolset.workspaceDir).path
                val isSelected = selectedFile?.absolutePath == file.absolutePath
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) Color(0xFFD97757) else Color(0xFF161B22))
                        .border(1.dp, Color(0xFF30363D), RoundedCornerShape(6.dp))
                        .clickable {
                            selectedFile = file
                            editorText = file.readText()
                            statusBanner = "Открыт: $rel (${file.length()} байт)"
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = rel,
                        color = if (isSelected) Color.White else Color(0xFFC9D1D9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Создание нового файла
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = newFileName,
                onValueChange = { newFileName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Новый файл (напр. src/main.py)", fontSize = 12.sp) },
                singleLine = true
            )
            Button(
                onClick = {
                    val name = newFileName.trim()
                    if (name.isNotEmpty()) {
                        val res = engine.toolset.writeFile(name, "# $name\n")
                        newFileName = ""
                        refreshTick++
                        val created = engine.toolset.resolveWorkspaceFile(name)
                        selectedFile = created
                        editorText = created.readText()
                        statusBanner = res.output
                        onLogEvent(
                            AgentStepEvent(
                                kind = EventKind.TOOL_RESULT,
                                title = "Write($name)",
                                body = res.output,
                                toolName = "Write",
                                diffPreview = res.diffPreview
                            )
                        )
                    }
                }
            ) {
                Text("+ Файл", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Text(
            text = statusBanner,
            color = Color(0xFF7EE787),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        // Редактор кода
        OutlinedTextField(
            value = editorText,
            onValueChange = { editorText = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFC9D1D9)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val file = selectedFile ?: return@Button
                    val rel = file.relativeTo(engine.toolset.workspaceDir).path
                    val res = engine.toolset.writeFile(rel, editorText)
                    refreshTick++
                    statusBanner = "Сохранено: ${res.output}"
                    onLogEvent(
                        AgentStepEvent(
                            kind = EventKind.TOOL_RESULT,
                            title = "Write($rel)",
                            body = res.output,
                            toolName = "Write",
                            diffPreview = res.diffPreview
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Сохранить (Write + Diff)", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }

            Button(
                onClick = {
                    val file = selectedFile ?: return@Button
                    val rel = file.relativeTo(engine.toolset.workspaceDir).path
                    val cmd = when {
                        rel.endsWith(".sh") -> "sh $rel"
                        rel.endsWith(".py") -> "python3 $rel 2>&1 || cat $rel"
                        else -> "cat $rel | head -n 30"
                    }
                    val res = engine.toolset.runBash(cmd)
                    statusBanner = "Вывод Bash: ${res.output.take(120)}"
                    onLogEvent(
                        AgentStepEvent(
                            kind = EventKind.TOOL_RESULT,
                            title = "Bash($cmd)",
                            body = res.output,
                            toolName = "Bash"
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Запустить в Bash", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ScreenInspectorTabView(
    engine: ClaudeCodeEngine,
    onOpenAccessibilitySettings: () -> Unit
) {
    var snapshot by remember { mutableStateOf(ScreenDriver.captureScreenSnapshot()) }
    var actionLog by remember { mutableStateOf("Готов к инспекции экрана и управлению жестами.") }
    var textToInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Статус AccessibilityService
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF161B22))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ScreenDriver.isEnabled) "ScreenDriver: АКТИВЕН ✅" else "ScreenDriver: ВЫКЛЮЧЕН ⚠️",
                    color = if (ScreenDriver.isEnabled) Color(0xFF7EE787) else Color(0xFFFF7B72),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "Пакет: ${snapshot.packageName} • Узлов: ${snapshot.nodes.size}",
                    color = Color(0xFF8B949E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Button(onClick = onOpenAccessibilitySettings) {
                Text("Настройки", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        // Панель быстрых действий экрана
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(onClick = {
                snapshot = ScreenDriver.captureScreenSnapshot()
                actionLog = "Обновлён снимок экрана (${snapshot.nodes.size} элементов)."
            }) {
                Text("Обновить дерево", fontSize = 11.sp)
            }
            Button(onClick = {
                val ok = ScreenDriver.performTestClick()
                actionLog = if (ok) "Тест M1: нажата первая доступная кнопка." else "Тест M1: не удалось (проверьте доступность)."
            }) {
                Text("Тест-клик (M1)", fontSize = 11.sp)
            }
            Button(onClick = {
                actionLog = ScreenDriver.scrollScreen("down")
            }) {
                Text("Скролл ↓", fontSize = 11.sp)
            }
            Button(onClick = {
                actionLog = ScreenDriver.scrollScreen("up")
            }) {
                Text("Скролл ↑", fontSize = 11.sp)
            }
            Button(onClick = {
                actionLog = ScreenDriver.performSystemAction("back")
            }) {
                Text("Назад", fontSize = 11.sp)
            }
            Button(onClick = {
                actionLog = ScreenDriver.performSystemAction("home")
            }) {
                Text("Домой", fontSize = 11.sp)
            }
        }

        // Быстрый ввод текста в активное поле
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = textToInput,
                onValueChange = { textToInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Текст для ACTION_SET_TEXT...", fontSize = 12.sp) },
                singleLine = true
            )
            Button(onClick = {
                if (textToInput.isNotBlank()) {
                    actionLog = ScreenDriver.inputText(textToInput)
                }
            }) {
                Text("Ввести", fontSize = 12.sp)
            }
        }

        Text(
            text = actionLog,
            color = Color(0xFF58A6FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        // Интерактивный список элементов дерева экрана
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF161B22))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (snapshot.nodes.isEmpty()) {
                item {
                    Text(
                        text = snapshot.formattedTree,
                        color = Color(0xFF8B949E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            } else {
                items(snapshot.nodes, key = { it.index }) { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0D1117))
                            .clickable {
                                actionLog = ScreenDriver.clickByIndex(node.index)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[${node.index}]",
                            color = Color(0xFFD97757),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.width(42.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${node.className} ${node.text.ifEmpty { node.contentDescription }.ifEmpty { "<без текста>" }}",
                                color = Color(0xFFC9D1D9),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Коорд: (${node.centerX}, ${node.centerY}) • click=${node.clickable} edit=${node.editable}",
                                color = Color(0xFF8B949E),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabView(engine: ClaudeCodeEngine) {
    val initial = remember { engine.loadConfig() }
    var provider by remember { mutableStateOf(initial.provider) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var maxIterations by remember { mutableStateOf(initial.maxIterations.toString()) }
    var savedBanner by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Конфигурация ИИ-ядра (Claude / OpenAI / DeepSeek / Локальный ReAct)",
            color = Color(0xFFD97757),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "auto" to "Авто",
                "anthropic" to "Anthropic Claude",
                "openai" to "OpenAI / Совмест."
            ).forEach { (key, label) ->
                Button(
                    onClick = {
                        provider = key
                        if (key == "anthropic") {
                            baseUrl = "https://api.anthropic.com"
                            model = "claude-sonnet-4-5"
                        } else if (key == "openai") {
                            baseUrl = "https://api.openai.com/v1"
                            model = "gpt-5"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (provider == key) Color(0xFFD97757) else Color(0xFF21262D)
                    )
                ) {
                    Text(label, fontSize = 11.sp)
                }
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL API") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Модель (claude-sonnet-4-5, gpt-5, deepseek-chat...)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Ключ (оставьте пустым для автономного локального режима)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = maxIterations,
            onValueChange = { maxIterations = it },
            label = { Text("Макс. шагов ReAct-цикла за один запрос (1-15)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = {
                engine.saveConfig(
                    ClaudeCodeEngine.ProviderConfig(
                        provider = provider,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        maxIterations = maxIterations.toIntOrNull() ?: 6
                    )
                )
                savedBanner = "Настройки сохранены ✅"
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить настройки агента", fontFamily = FontFamily.Monospace)
        }

        if (savedBanner.isNotEmpty()) {
            Text(
                text = savedBanner,
                color = Color(0xFF7EE787),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        // Блок политики безопасности
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF161B22))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "🛡️ Аппаратные ограничения безопасности (Неотключаемые):",
                color = Color(0xFF58A6FF),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = "• Чёрный список пакетов: ${Blocklist.PACKAGES.joinToString(", ")}",
                color = Color(0xFFC9D1D9),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = "• Авто-стоп при капче (Cloudflare/reCAPTCHA) и экранах ввода СМС/CVV/карт.",
                color = Color(0xFFC9D1D9),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}
