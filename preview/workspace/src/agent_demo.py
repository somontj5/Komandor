#!/usr/bin/env python3
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
