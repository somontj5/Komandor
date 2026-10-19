package com.komandor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.komandor.app.accessibility.ScreenDriver
import com.komandor.app.service.AgentService

/**
 * Terminal UI (этап M1): простой чат с агентом.
 * Сообщения хранятся в памяти; позже сюда подключится Room-память.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D1117),
                    surface = Color(0xFF161B22),
                    primary = Color(0xFF58A6FF)
                )
            ) {
                TerminalScreen()
            }
        }
    }
}

data class TermMessage(val text: String, val fromUser: Boolean)

@Composable
fun TerminalScreen() {
    val messages = remember {
        mutableStateListOf(
            TermMessage("Командор готов. Напишите задачу обычным языком.", false)
        )
    }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Перечитываем статус сервиса при каждом входе на экран
    var serviceRunning by remember { mutableStateOf(AgentService.isRunning) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Верхняя панель: статус сервиса
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (serviceRunning) "Агент: работает" else "Агент: остановлен",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (serviceRunning) {
                    context.stopService(Intent(context, AgentService::class.java))
                    serviceRunning = false
                    messages.add(TermMessage("Агент остановлен.", false))
                } else {
                    context.startForegroundService(
                        Intent(context, AgentService::class.java)
                    )
                    serviceRunning = true
                    messages.add(TermMessage("Агент запущен.", false))
                }
            }) {
                Text(if (serviceRunning) "Стоп" else "Старт")
            }
        }

        // Лента сообщений терминала
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            items(messages) { msg ->
                Text(
                    text = if (msg.fromUser) "Вы: ${msg.text}" else msg.text,
                    color = if (msg.fromUser) Color(0xFF7EE787) else Color(0xFFC9D1D9),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        // Поле ввода и кнопка отправки
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите команду…") },
                singleLine = true
            )
            Button(onClick = {
                if (input.isNotBlank()) {
                    val text = input.trim()
                    messages.add(TermMessage(text, true))
                    input = ""
                    // Тест M1: команда "тест" пробует нажать первую кнопку на экране
                    if (text.equals("тест", ignoreCase = true)) {
                        val ok = ScreenDriver.performTestClick()
                        messages.add(
                            if (ok) TermMessage("Тест: нажатие выполнено.", false)
                            else TermMessage(
                                "Тест: не удалось. Включите «Командор — драйвер экрана» в настройках доступности.",
                                false
                            )
                        )
                    }
                }
            }) {
                Text("➤")
            }
        }
    }
}
