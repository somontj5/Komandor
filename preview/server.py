#!/usr/bin/env python3
"""
Komandor • Claude Code Mobile — Live Companion & Interactive Android Emulator Server.
Позволяет протестировать все инструменты Командора (Bash, Read, Write, Edit, Glob, Grep,
TodoWrite, ScreenInspect, ScreenAction, AppLaunch и защиту Blocklist) прямо в браузере,
а также просматривать исходники Android-приложения (Kotlin + Jetpack Compose).
"""

import datetime
import difflib
import json
import os
import re
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREVIEW_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_DIR = os.path.join(PREVIEW_DIR, "workspace")


def seed_workspace():
    os.makedirs(os.path.join(WORKSPACE_DIR, "src"), exist_ok=True)
    os.makedirs(os.path.join(WORKSPACE_DIR, "scripts"), exist_ok=True)

    claude_md = os.path.join(WORKSPACE_DIR, "CLAUDE.md")
    if not os.path.exists(claude_md):
        with open(claude_md, "w", encoding="utf-8") as f:
            f.write(
                """# CLAUDE.md — Инструкции агента Командор (Claude Code Mobile)

## О проекте
Командор — автономный ИИ-агент и мобильная среда разработки в стиле Claude Code для Android.
Агент совмещает:
1. Файловые и терминальные инструменты (`Bash`, `Read`, `Write`, `Edit`, `Glob`, `Grep`, `TodoWrite`)
2. Управление приложениями телефона через `AccessibilityService` (`ScreenInspect`, `ScreenAction`, `AppLaunch`)

## Правила безопасности
- Никогда не открывать и не автоматизировать WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`).
- При обнаружении капчи, 2FA/СМС-кода или экрана ввода банковской карты — немедленно остановиться и передать управление человеку.
- Перед редактированием файла через `Edit` всегда читать его через `Read`.
"""
            )

    demo_py = os.path.join(WORKSPACE_DIR, "src", "agent_demo.py")
    if not os.path.exists(demo_py):
        with open(demo_py, "w", encoding="utf-8") as f:
            f.write(
                """#!/usr/bin/env python3
# Демо-скрипт в локальном воркспейсе Командора

def analyze_tasks(tasks):
    completed = [t for t in tasks if t.get("done")]
    return f"Выполнено {len(completed)} из {len(tasks)} задач"

if __name__ == "__main__":
    sample = [
        {"title": "Проверить экран через ScreenDriver", "done": True},
        {"title": "Сгенерировать патч через Edit + Diff", "done": True},
        {"title": "Запустить сборку APK", "done": True},
    ]
    print(analyze_tasks(sample))
"""
            )

    dev_sh = os.path.join(WORKSPACE_DIR, "scripts", "device_info.sh")
    if not os.path.exists(dev_sh):
        with open(dev_sh, "w", encoding="utf-8") as f:
            f.write(
                """#!/bin/sh
echo "=== Командор Claude Code Mobile Environment ==="
echo "Рабочая папка: $(pwd)"
echo "Архитектура: $(uname -m)"
echo "Файлы проекта:"
ls -la
"""
            )


seed_workspace()

TODOS = [
    {"id": "1", "content": "Инициализировать рабочее окружение Командор Claude Code", "status": "completed"},
    {"id": "2", "content": "Подключить ScreenDriver (Accessibility) и полный набор из 10 инструментов", "status": "completed"},
    {"id": "3", "content": "Выполнять задачи пользователя в автономном цикле ReAct", "status": "in_progress"},
]

SIMULATED_SCREEN_STATE = {
    "package": "com.anthropic.claude",
    "nodes": [
        {"index": 1, "class": "ImageButton", "text": "", "desc": "Новое обсуждение", "id": "btn_new_chat", "clickable": True, "editable": False, "x": 96, "y": 148},
        {"index": 2, "class": "TextView", "text": "Claude 4.5 Sonnet • Mobile Workspace", "desc": "", "id": "tv_model_title", "clickable": True, "editable": False, "x": 540, "y": 148},
        {"index": 3, "class": "EditText", "text": "Спросите Claude или вставьте код...", "desc": "Поле ввода сообщения", "id": "et_prompt", "clickable": True, "editable": True, "x": 460, "y": 1820},
        {"index": 4, "class": "Button", "text": "Отправить", "desc": "Отправить запрос", "id": "btn_send", "clickable": True, "editable": False, "x": 960, "y": 1820},
        {"index": 5, "class": "Button", "text": "Прикрепить файл из Командора", "desc": "", "id": "btn_attach", "clickable": True, "editable": False, "x": 180, "y": 1940},
        {"index": 6, "class": "ScrollView", "text": "Лента сообщений чата", "desc": "", "id": "scroll_messages", "clickable": False, "editable": False, "x": 540, "y": 980},
    ],
}


def safe_workspace_path(rel_path: str) -> str:
    cleaned = rel_path.strip().lstrip("/").replace("..", "")
    if not cleaned:
        cleaned = "CLAUDE.md"
    full = os.path.abspath(os.path.join(WORKSPACE_DIR, cleaned))
    if not full.startswith(os.path.abspath(WORKSPACE_DIR)):
        return os.path.join(WORKSPACE_DIR, "CLAUDE.md")
    return full


def unified_diff_str(filename: str, old_text: str, new_text: str) -> str:
    diff_lines = list(
        difflib.unified_diff(
            old_text.splitlines(),
            new_text.splitlines(),
            fromfile=f"a/{filename}",
            tofile=f"b/{filename}",
            lineterm="",
        )
    )
    return "\n".join(diff_lines[:40]) if diff_lines else "@@ Без изменений содержимого @@"


def format_screen_tree():
    pkg = SIMULATED_SCREEN_STATE["package"]
    nodes = SIMULATED_SCREEN_STATE["nodes"]
    lines = [
        f"Активный пакет: {pkg}",
        "Безопасность: РАЗРЕШЕНО (WhatsApp Blocklist активен, капч не обнаружено)",
        f"Элементов обнаружено: {len(nodes)}",
        "---",
    ]
    for n in nodes:
        flags = []
        if n.get("clickable"):
            flags.append("CLICK")
        if n.get("editable"):
            flags.append("EDIT")
        label = f"\"{n['text']}\"" if n["text"] else f"({n['desc']})"
        lines.append(
            f"[{n['index']}] {n['class']} #{n['id']} {label} [{','.join(flags)}] @({n['x']},{n['y']})"
        )
    return "\n".join(lines)


def execute_tool(tool_name: str, args: dict):
    t = tool_name.lower()
    if t == "glob":
        pattern = args.get("pattern", "*")
        files = []
        for root, _, filenames in os.walk(WORKSPACE_DIR):
            for fn in sorted(filenames):
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, WORKSPACE_DIR)
                if pattern in ("*", "") or pattern.replace("*", "") in rel:
                    files.append(f"  • {rel} ({os.path.getsize(full)} B)")
        out = f"Найдено файлов ({len(files)}) в workspace:\n" + "\n".join(files)
        return {"tool": "Glob", "success": True, "output": out}

    if t == "read":
        rel = args.get("file_path", "CLAUDE.md")
        full = safe_workspace_path(rel)
        if not os.path.isfile(full):
            return {"tool": "Read", "success": False, "output": f"Файл не найден: {rel}"}
        with open(full, "r", encoding="utf-8") as f:
            lines = f.read().splitlines()
        numbered = [f"// Файл: {os.path.relpath(full, WORKSPACE_DIR)} ({len(lines)} строк)"]
        for idx, line in enumerate(lines[:200], start=1):
            numbered.append(f"{idx:4d} | {line}")
        return {"tool": "Read", "success": True, "output": "\n".join(numbered)}

    if t == "write":
        rel = args.get("file_path", "output.py")
        content = args.get("content", "")
        full = safe_workspace_path(rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        old = ""
        if os.path.isfile(full):
            with open(full, "r", encoding="utf-8") as f:
                old = f.read()
        with open(full, "w", encoding="utf-8") as f:
            f.write(content)
        diff = unified_diff_str(rel, old, content)
        return {
            "tool": "Write",
            "success": True,
            "output": f"Записан файл {rel} ({len(content.splitlines())} строк, {len(content)} байт)",
            "diff": diff,
        }

    if t == "edit":
        rel = args.get("file_path", "")
        old_s = args.get("old_string", "")
        new_s = args.get("new_string", "")
        full = safe_workspace_path(rel)
        if not os.path.isfile(full):
            return {"tool": "Edit", "success": False, "output": f"Файл не найден: {rel}"}
        with open(full, "r", encoding="utf-8") as f:
            orig = f.read()
        if not old_s or old_s not in orig:
            return {"tool": "Edit", "success": False, "output": f"Фрагмент old_string не найден в {rel}"}
        updated = orig.replace(old_s, new_s, 1)
        with open(full, "w", encoding="utf-8") as f:
            f.write(updated)
        diff = unified_diff_str(rel, orig, updated)
        return {"tool": "Edit", "success": True, "output": f"Патч применён к {rel}", "diff": diff}

    if t == "grep":
        pat = args.get("pattern", "def ")
        matches = []
        for root, _, filenames in os.walk(WORKSPACE_DIR):
            for fn in sorted(filenames):
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, WORKSPACE_DIR)
                try:
                    with open(full, "r", encoding="utf-8") as f:
                        for i, line in enumerate(f, start=1):
                            if re.search(pat, line, re.IGNORECASE):
                                matches.append(f"{rel}:{i}: {line.strip()}")
                except Exception:
                    pass
        out = (
            f"Найдено совпадений ({len(matches)}):\n" + "\n".join(matches)
            if matches
            else f"Совпадений по '{pat}' не найдено."
        )
        return {"tool": "Grep", "success": True, "output": out}

    if t == "bash":
        cmd = args.get("command", "ls -la")
        if "whatsapp" in cmd.lower() or "rm -rf /" in cmd.lower():
            return {
                "tool": "Bash",
                "success": False,
                "output": "БЛОКИРОВКА БЕЗОПАСНОСТИ (Blocklist): Команда заблокирована защитным фильтром Командора.",
            }
        try:
            proc = subprocess.run(
                cmd,
                shell=True,
                cwd=WORKSPACE_DIR,
                capture_output=True,
                text=True,
                timeout=8,
            )
            out = (proc.stdout + proc.stderr).strip()
            return {
                "tool": "Bash",
                "success": proc.returncode == 0,
                "output": out if out else f"(exit code {proc.returncode}, без вывода)",
            }
        except Exception as e:
            return {"tool": "Bash", "success": False, "output": f"Ошибка shell: {e}"}

    if t == "screeninspect":
        return {"tool": "ScreenInspect", "success": True, "output": format_screen_tree()}

    if t == "screenaction":
        act = args.get("action", "click_index")
        if act == "click_index":
            idx = int(args.get("index", 1))
            node = next((n for n in SIMULATED_SCREEN_STATE["nodes"] if n["index"] == idx), None)
            if not node:
                return {"tool": "ScreenAction", "success": False, "output": f"Узел [{idx}] не найден."}
            label = node["text"] or node["desc"] or node["id"]
            return {
                "tool": "ScreenAction(click_index)",
                "success": True,
                "output": f"Выполнен клик по элементу [{idx}] {node['class']} \"{label}\" @({node['x']},{node['y']})",
            }
        if act == "type":
            txt = args.get("text", "")
            for n in SIMULATED_SCREEN_STATE["nodes"]:
                if n.get("editable"):
                    n["text"] = txt
            return {
                "tool": "ScreenAction(type)",
                "success": True,
                "output": f"Текст «{txt}» введён в поле [3] EditText (#et_prompt) через ACTION_SET_TEXT.",
            }
        if act == "scroll":
            direction = args.get("direction", "down")
            return {
                "tool": "ScreenAction(scroll)",
                "success": True,
                "output": f"Жест прокрутки ({direction}) успешно выполнен через ScreenDriver.",
            }
        return {
            "tool": f"ScreenAction({act})",
            "success": True,
            "output": f"Системное действие '{act}' выполнено.",
        }

    if t == "applaunch":
        app = args.get("app", "").strip()
        if "whatsapp" in app.lower() or "ватсап" in app.lower():
            return {
                "tool": "AppLaunch",
                "success": False,
                "output": "БЛОКИРОВКА БЕЗОПАСНОСТИ: Пакет com.whatsapp (WhatsApp) находится в неотключаемом чёрном списке Blocklist.kt!",
            }
        pkg_map = {
            "claude": "com.anthropic.claude",
            "клауд": "com.anthropic.claude",
            "gemini": "com.google.android.apps.bard",
            "genspark": "ai.genspark.app",
            "termux": "com.termux",
            "калькулятор": "com.google.android.calculator",
        }
        pkg = pkg_map.get(app.lower(), app or "com.anthropic.claude")
        SIMULATED_SCREEN_STATE["package"] = pkg
        return {
            "tool": "AppLaunch",
            "success": True,
            "output": f"Приложение '{app}' ({pkg}) запущено. ScreenDriver переключил контекст окна.",
        }

    return {"tool": tool_name, "success": True, "output": "Выполнено."}


def process_prompt(prompt: str):
    p = prompt.strip()
    lower = p.lower()
    steps = []

    if p.startswith("/"):
        parts = p.split(" ", 1)
        cmd = parts[0].lower()
        arg = parts[1].strip() if len(parts) > 1 else ""
        if cmd in ("/help", "/?"):
            steps.append({
                "kind": "SYSTEM",
                "title": "Справка Claude Code Mobile (Командор v2.0)",
                "body": (
                    "Доступные слэш-команды:\n"
                    "• /status     — диагностика агента, сервиса, ScreenDriver и воркспейса\n"
                    "• /ls         — показать все файлы в локальном рабочем каталоге\n"
                    "• /read <путь> — прочитать файл с нумерацией строк\n"
                    "• /bash <кмд> — выполнить shell-команду в песочнице проекта\n"
                    "• /grep <стр> — найти текст по всем файлам проекта\n"
                    "• /todo       — показать текущий список задач сессии\n"
                    "• /screen     — снять дерево элементов текущего экрана телефона\n"
                    "• /open <имя> — запустить разрешённое приложение (claude, gemini, genspark, whatsapp...)"
                ),
            })
            return steps
        if cmd in ("/status", "/doctor"):
            steps.append({
                "kind": "SYSTEM",
                "title": "Диагностика системы (/doctor)",
                "body": (
                    "• Движок: Командор Claude Code Engine v2.0 (Android Kotlin + Jetpack Compose)\n"
                    f"• Рабочий каталог: {WORKSPACE_DIR}\n"
                    "• ScreenDriver (Accessibility): АКТИВЕН ✅\n"
                    "• Защита WhatsApp Blocklist: АКТИВНА 🛡️ (com.whatsapp, com.whatsapp.w4b)\n"
                    "• Арсенал инструментов: Bash, Read, Write, Edit, Glob, Grep, TodoWrite, ScreenInspect, ScreenAction, AppLaunch"
                ),
            })
            return steps
        if cmd in ("/ls", "/files"):
            res = execute_tool("Glob", {"pattern": arg or "*"})
            steps.append({"kind": "TOOL_RESULT", "title": f"Glob({arg or '*'})", "body": res["output"]})
            return steps
        if cmd in ("/read", "/cat"):
            target = arg or "CLAUDE.md"
            res = execute_tool("Read", {"file_path": target})
            steps.append({
                "kind": "TOOL_RESULT" if res["success"] else "ERROR",
                "title": f"Read({target})",
                "body": res["output"],
            })
            return steps
        if cmd in ("/bash", "/sh", "/run"):
            target = arg or "python3 src/agent_demo.py && ls -la"
            res = execute_tool("Bash", {"command": target})
            steps.append({
                "kind": "TOOL_RESULT" if res["success"] else "ERROR",
                "title": f"Bash({target})",
                "body": res["output"],
            })
            return steps
        if cmd in ("/grep", "/search"):
            target = arg or "def "
            res = execute_tool("Grep", {"pattern": target})
            steps.append({"kind": "TOOL_RESULT", "title": f"Grep({target})", "body": res["output"]})
            return steps
        if cmd in ("/screen", "/ui"):
            res = execute_tool("ScreenInspect", {})
            steps.append({"kind": "TOOL_RESULT", "title": "ScreenInspect()", "body": res["output"]})
            return steps
        if cmd in ("/todo", "/tasks"):
            lines = ["План выполнения (Claude Code Todo List):"]
            for t in TODOS:
                icon = "[✓]" if t["status"] == "completed" else ("[●]" if t["status"] == "in_progress" else "[ ]")
                lines.append(f"{icon} #{t['id']} {t['content']} ({t['status']})")
            steps.append({"kind": "TOOL_RESULT", "title": "TodoWrite", "body": "\n".join(lines)})
            return steps
        if cmd in ("/open", "/launch"):
            res = execute_tool("AppLaunch", {"app": arg or "claude"})
            steps.append({
                "kind": "TOOL_RESULT" if res["success"] else "ERROR",
                "title": f"AppLaunch({arg or 'claude'})",
                "body": res["output"],
            })
            return steps

    if lower == "тест":
        steps.append({
            "kind": "THINKING",
            "title": "Проверка драйвера экрана (M1)",
            "body": "Выполняю ScreenDriver.performTestClick() на первой кликабельной кнопке активного окна...",
        })
        res = execute_tool("ScreenAction", {"action": "click_index", "index": 1})
        steps.append({
            "kind": "TOOL_RESULT",
            "title": "ScreenDriver.performTestClick()",
            "body": "Тест: нажатие выполнено. (" + res["output"] + ")",
        })
        return steps

    if "whatsapp" in lower or "ватсап" in lower or "вотсап" in lower:
        steps.append({
            "kind": "THINKING",
            "title": "Проверка политики безопасности (Blocklist.kt)",
            "body": "Обнаружена попытка обращения к пакету WhatsApp. Проверяю правила безопасности...",
        })
        res = execute_tool("AppLaunch", {"app": "com.whatsapp"})
        steps.append({
            "kind": "ERROR",
            "title": "Заблокировано системой безопасности",
            "body": res["output"],
        })
        return steps

    create_match = re.search(
        r"(?:создай|напиши|сгенерируй|create|write)\s+(?:файл|скрипт|код|file|script)?\s*([a-zA-Z0-9_./-]+\.[a-zA-Z0-9]+)",
        p,
        re.IGNORECASE,
    )
    if create_match:
        fname = create_match.group(1)
        steps.append({
            "kind": "THINKING",
            "title": f"Планирование разработки ({fname})",
            "body": f"1. Генерирую код для `{fname}`.\n2. Записываю через инструмент Write с построением unified diff.\n3. Запускаю проверку через Bash.",
        })
        code = (
            f"#!/usr/bin/env python3\n"
            f"# Сгенерировано агентом Командор (Claude Code Mobile v2.0)\n"
            f"# Запрос: {p}\n\n"
            f"import datetime\n\n"
            f"def run_task():\n"
            f"    print('⚡ Командор Claude Code выполнил модуль {fname}!')\n"
            f"    print('Время:', datetime.datetime.now().isoformat())\n\n"
            f"if __name__ == '__main__':\n"
            f"    run_task()\n"
        )
        w_res = execute_tool("Write", {"file_path": fname, "content": code})
        steps.append({
            "kind": "TOOL_RESULT",
            "title": f"Write({fname})",
            "body": w_res["output"],
            "diff": w_res.get("diff"),
        })
        if fname.endswith(".py"):
            b_res = execute_tool("Bash", {"command": f"python3 {fname}"})
            steps.append({
                "kind": "TOOL_RESULT",
                "title": f"Bash(python3 {fname})",
                "body": b_res["output"],
            })
        steps.append({
            "kind": "ASSISTANT",
            "title": "Командор Claude Code",
            "body": f"Готово! Файл `{fname}` создан, проверен в песочнице и доступен во вкладке **Файлы**.",
        })
        return steps

    # Многошаговый ReAct цикл по умолчанию
    steps.append({
        "kind": "THINKING",
        "title": "Автономный цикл ReAct (Think → Act → Observe)",
        "body": f"Декомпозирую задачу: «{p}».\nШаг 1: Сканирую проект через Glob(*)\nШаг 2: Проверяю дерево экрана через ScreenInspect()\nШаг 3: Выполняю скрипт диагностики в Bash.",
    })
    g_res = execute_tool("Glob", {"pattern": "*"})
    steps.append({"kind": "TOOL_RESULT", "title": "Glob(*)", "body": g_res["output"]})

    s_res = execute_tool("ScreenInspect", {})
    steps.append({"kind": "TOOL_RESULT", "title": "ScreenInspect()", "body": s_res["output"]})

    b_res = execute_tool("Bash", {"command": "python3 src/agent_demo.py"})
    steps.append({"kind": "TOOL_RESULT", "title": "Bash(python3 src/agent_demo.py)", "body": b_res["output"]})

    steps.append({
        "kind": "ASSISTANT",
        "title": "Командор Claude Code",
        "body": (
            f"Задача **«{p}»** обработана цепочкой инструментов Claude Code Mobile.\n\n"
            "• Попробуйте написать: `создай файл solver.py` — чтобы увидеть генерацию кода, unified diff и автозапуск.\n"
            "• Напишите `/open whatsapp` — чтобы проверить жёсткую блокировку безопасности Blocklist.\n"
            "• Переключитесь на вкладки **Файлы**, **Экран UI** или **Исходники APK (Kotlin)** справа!"
        ),
    })
    return steps


class KomandorHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send_json(self, data, status=200):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
            return
        if parsed.path in ("/", "/index.html"):
            index_file = os.path.join(PREVIEW_DIR, "index.html")
            with open(index_file, "rb") as f:
                content = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return

        if parsed.path == "/api/state":
            ws_files = []
            for root, _, filenames in os.walk(WORKSPACE_DIR):
                for fn in sorted(filenames):
                    full = os.path.join(root, fn)
                    rel = os.path.relpath(full, WORKSPACE_DIR)
                    with open(full, "r", encoding="utf-8", errors="replace") as f:
                        ws_files.append({"path": rel, "content": f.read(), "size": os.path.getsize(full)})

            kotlin_sources = []
            kt_files = [
                "app/src/main/java/com/komandor/app/MainActivity.kt",
                "app/src/main/java/com/komandor/app/agent/ClaudeCodeEngine.kt",
                "app/src/main/java/com/komandor/app/tools/ClaudeCodeToolset.kt",
                "app/src/main/java/com/komandor/app/accessibility/ScreenDriver.kt",
                "app/src/main/java/com/komandor/app/security/Blocklist.kt",
                "app/src/main/java/com/komandor/app/service/AgentService.kt",
            ]
            for rel_kt in kt_files:
                full_kt = os.path.join(ROOT_DIR, rel_kt)
                if os.path.isfile(full_kt):
                    with open(full_kt, "r", encoding="utf-8") as f:
                        txt = f.read()
                    kotlin_sources.append({
                        "path": rel_kt,
                        "name": os.path.basename(rel_kt),
                        "lines": len(txt.splitlines()),
                        "content": txt,
                    })

            self._send_json({
                "workspace": ws_files,
                "screen": SIMULATED_SCREEN_STATE,
                "screenTree": format_screen_tree(),
                "todos": TODOS,
                "kotlinSources": kotlin_sources,
            })
            return

        self._send_json({"error": "Not found"}, 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length", "0"))
        body_raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        payload = json.loads(body_raw or "{}")

        if parsed.path == "/api/prompt":
            prompt = payload.get("prompt", "")
            steps = process_prompt(prompt)
            self._send_json({"steps": steps, "screen": SIMULATED_SCREEN_STATE, "screenTree": format_screen_tree()})
            return

        if parsed.path == "/api/tool":
            tool_name = payload.get("tool", "Glob")
            args = payload.get("args", {})
            res = execute_tool(tool_name, args)
            self._send_json({
                "result": res,
                "screen": SIMULATED_SCREEN_STATE,
                "screenTree": format_screen_tree(),
            })
            return

        self._send_json({"error": "Unknown endpoint"}, 404)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    server = HTTPServer(("0.0.0.0", port), KomandorHandler)
    print(f"Komandor Claude Code Mobile Live Server listening on http://0.0.0.0:{port}")
    server.serve_forever()
