# evals

- `live/` — заготовленные диалоги агент-пользователь ↔ агент-ассистент на запущенной `atlas-run` Jira. Без моков, результат — транскрипт. См. `live/README.md`.
- `offline/` — прежний харнесс: simulated backend, моки, offline-тесты grader'ов, платные simulated-прогоны. См. `offline/README.md`.
- Обязательный **новый** комментарий на переходе: offline-проверки протокола и модели состояния — в `offline/README.md`; live-сценарии с XML/UI evidence — в `live/README.md`. Наличие реализованного verifier не означает выполненный online-тест. Новые live-сценарии используют read-only setup и `--human-user`, а не автоматическое разрешение модели пользователя.

Секреты и overrides моделей — в локальном `evals/.env` (не коммитится); имена переменных — в `.env.example`.
