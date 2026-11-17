# Живые диалоги на atlas-run

Агент-пользователь (LLM) разговаривает с агентом-ассистентом (LLM), который работает как обычный клиент omp с подключённым MCP плагина на запущенной `atlas-run` Jira. Моков и заглушек нет: сохраняются транскрипт, вызовы инструментов и результаты проверок.

- Ассистент — настоящий `omp -p` в отдельной временной папке агента с `mcp.json` на `/rest/control/1.0/mcp` и в пустой рабочей папке. Флаг `--no-tools` убирает встроенные инструменты omp (bash, read и т.д.), MCP остаётся: опубликованные инструменты плагина (`src/main/resources/llm/*-tools.json`) — единственные нативные функции модели. Инструкции сервера модель получает так же, как пользователь omp (omp обрезает их до 4 000 символов). Сценарий ассистент не видит.
- Подтверждения omp отключены (`--approval-mode=yolo`): подтверждение плана даёт агент-пользователь в диалоге, а не omp.
- Ход за ходом это одна сессия omp: она хранится в `runs/<время>/assistant-session/`, следующие ходы идут с `--continue`.
- Агент-пользователь получает весь `scenario.md`, разрешённые `prepared.known` и видимую переписку, но не каталог tools, MCP server instructions или остальной setup. Его отдельная временная папка получает модельную конфигурацию/хранилища, но не исходный `mcp.json`; запрос идёт с `--no-tools --no-rules`, без MCP подключения. Ассистент по-прежнему получает подключение плагина. При переносе иной конфигурации omp нужно отдельно проверять, что она не подключает MCP пользователю.
- Все вызовы идут в Jira по-настоящему, включая `start_operation`. Очистки нет: созданное остаётся в atlas-run.
- Разрешены только `localhost`/`127.0.0.1`.

## Диалог

Одна папка — один диалог:

```text
scenarios/<имя>/
  scenario.md
  setup.mjs                    ← необязательная подготовка Jira перед диалогом
  hooks.mjs                    ← необязательное вмешательство в Jira между ходами
  verify.mjs                   ← необязательные проверки сценария, только чтение
  runs/<время>/setup.json      ← что создала подготовка
  runs/<время>/conversation.md   ← видимые реплики
  runs/<время>/tool-trace.json   ← вызовы tools из событий omp: аргументы, ответ, isError, время
  runs/<время>/lifecycle.json   ← реальные промежуточные наблюдения и вмешательства hook
  runs/<время>/verdict.json     ← результаты общих и сценарных проверок
  runs/<время>/assistant-session/ ← сессия omp ассистента: сообщения и вызовы tools (системного промпта в ней нет)
```

`runs/` не коммитится. В `scenario.md` обязателен раздел `## Первая реплика`: он отправляется ассистенту дословно. Остальное — свободный текст для агента-пользователя: кто он, что знает и говорит только по вопросу, как себя вести, когда заканчивать (DONE/GIVE_UP).

`{{RUN}}` в любом месте `scenario.md` заменяется коротким идентификатором прогона (например `KX3QZ`) — одинаково для ассистента и агента-пользователя. Используйте его в именах создаваемых объектов, чтобы повторный прогон не упирался в объект прошлого прогона.

### Случайные названия

Названия создаваемых объектов не пишутся в `scenario.md` фиксированно, их выбирает runner заново при каждом запуске:

- `{{NAME:ключ}}` — «Прилагательное Существительное КОД», например `Cobalt Fox RSU`. Так имя почти не повторится между прогонами, и Jira не ответит EXISTS на объект прошлого прогона.
- `{{WORD:ключ}}` — одно существительное (значения списков, опции), разное для каждого ключа в прогоне.
- `{{KEY:ключ}}` — ключ проекта Jira из пяти заглавных латинских букв, например `QZRTK`.

Один и тот же `ключ` даёт одно и то же значение во всём `scenario.md`, поэтому первая реплика и инструкции агента-пользователя всегда совпадают. Выбранные значения печатаются при `--check` и записываются в заголовок `conversation.md`. Любой другой `{{...}}` в тексте — ошибка, а не молчаливо оставленный текст.

### Подготовка (`setup.mjs`)

Если сценарию нужны объекты, которых ассистент не создаёт сам (задачи в определённых статусах, поле-дубликат, черновик workflow), их создаёт `setup.mjs` до первой реплики. Его `export default async function` получает:

- `names` — те же случайные значения, что в `scenario.md` (`names['KEY:proj']`, `names['NAME:wf']`), и `runId`;
- `tool(name, args)` — вызов инструмента плагина через MCP; `apply(name, args)` — preview, `start_operation` и ожидание итога, возвращает результат операции; превью без плана — ошибка;
- `probe(name, args)` — вызов MCP без исключения на ожидаемый отказ; возвращает `{ isError, data, text }`.
- `rest(method, path, body)` — JSON REST Jira; `adminPage(path, params)` — админские действия Jira без REST (GET без `params`, form POST с ними);
- `log(message)`.

Возвращённый объект сохраняется в `runs/<время>/setup.json`. Ошибка подготовки останавливает прогон до LLM (код 2). Подготовка каждый раз создаёт новые объекты под новыми случайными именами, очистки нет.

Необязательное поле `known` возвращённого объекта передаётся только агенту-пользователю как дополнительные факты раздела «Что ты знаешь» (отвечать только по вопросу). Используйте его для фактических имён и значений, выбранных подготовкой из каталогов Jira; остальные поля `prepared` остаются доступны проверкам, но не модели. Первая реплика по-прежнему берётся дословно из `scenario.md`.

### Между ходами (`hooks.mjs`)

Необязательный default export вызывается после ответа ассистента, **до следующей реплики пользователя**. Получает `{ jira, prepared, lifecycle, trace, conversation, turn, tool, rest, adminPage, log }`. `prepared` — результат setup, `lifecycle` — изменяемый объект наблюдений, который сохраняется в `lifecycle.json` вместе с транскриптом после каждого хода, в том числе при ошибке hook. `jira` содержит адрес и авторизацию: не сохранять её или cookies в артефакты.

Hook предназначен для настоящих внешних изменений и наблюдений, а не подмены ответов MCP. Его действия отделены от `tool-trace.json` ассистента и должны иметь собственные доказательства в `lifecycle`. `--check`, `--setup-only` и `--regrade` hook не выполняют. Ошибка hook останавливает диалог с кодом 1; повторная проверка читает сохранённые наблюдения и не повторяет запись.

### Проверки после диалога

Диалог не считается пройденным только потому, что агент-пользователь сказал DONE. После него runner выполняет проверки и пишет `runs/<время>/verdict.json` (`passed`, `checks` с классами, `observations` — неуспешные advisory). В консоли — PASS/FAIL по contract, `OBSERVATION` по advisory-сбою и `ИТОГ: PASS/FAIL`. Advisory-only сбои сохраняют PASS и код `0`; любой contract-сбой, в том числе смешанный с advisory, даёт FAIL и код `3`. Отсутствующий класс старых проверок означает contract; неизвестный класс и исключение verifier не могут дать PASS.

- Общие проверки по `tool-trace.json` и диалогу (`checks.mjs`) для всех сценариев: нет `dryRun:false` вне `start_operation`; каждый `start_operation` идёт по `planId` превью того же инструмента; превью и apply в разных ходах (показ плана и «да» не в одном ходу); `planId` не применяется дважды; вызываются только инструменты из каталога плагина (`src/main/resources/llm/*-tools.json`), без внешних fetch, web и shell; `planId` не попадает в ответы ассистента; после «да» на план, показанный в прошлом ходе, ассистент применяет его или строит новое превью, а не переспрашивает (неотвеченное «да», например при сбое провайдера, не учитывается).
- Необязательный `scenarios/<имя>/verify.mjs`: `export default async function ({ names, runId, prepared, lifecycle, trace, conversation, tool, probe, rest, adminPage, log })` возвращает `[{ name, ok, detail, class }]`, где `class` — `contract` или `advisory` (старые результаты без класса остаются contract). Читает итоговое состояние Jira и диалог; `lifecycle` — сохранённые промежуточные наблюдения hook; `probe` возвращает `{ isError, data, text }` без исключения. Только чтение.
- Повторная проверка сохранённого прогона без LLM: `node evals/live/run.mjs --regrade evals/live/scenarios/<имя>/runs/<время>`. Имена и run id берутся из `conversation.md`, `prepared` из `setup.json`, состояние Jira читается текущее.

## Запуск

```powershell
atlas-run                                                   # Jira на http://localhost:2990/jira
node evals/live/run.mjs purple-octopus-engine --check       # MCP + сценарий, без LLM и без подготовки
node evals/live/run.mjs field-audit-readonly --setup-only   # только подготовка, без LLM
node evals/live/run.mjs purple-octopus-engine --max-turns 15
```

`--assistant-model MODEL` и `--user-model MODEL` выбирают модели независимо: CLI → соответствующая `DIALOG_ASSISTANT_MODEL` / `DIALOG_USER_MODEL` → модель omp по умолчанию (флаг `--model` не передаётся). Старые команды без новых флагов и env-настройки совместимы. Отсутствующий, пустой или пробельный аргумент модели — ошибка до Jira/моделей. При `--human-user` основного диалога пользовательская модель не запускается, но opt-in setup всё равно требует своего настроенного simulated-user. `--check` и `--regrade` моделей не вызывают; `--setup-only` вызывает их только для opt-in подтверждения.

`--max-turns N` — положительное целое, по умолчанию **15**; после N ответов ассистента цикл завершается с причиной «лимит N ходов» (если пользователь не завершил раньше). Этот лимит не означает успешность проверок.

Переменные окружения (все необязательны):

| Переменная | По умолчанию |
|---|---|
| `JIRA_BASE_URL` | `http://localhost:2990/jira` |
| `JIRA_USERNAME` / `JIRA_PASSWORD` | `admin` / `admin` (setup REST и admin-страницы) |
| `JIRA_TOKEN` | нет; обязателен: personal access token администратора для MCP (эндпоинт принимает только `Authorization: Bearer`) |
| `LIVE_ISSUE_TYPE` | нет значения по умолчанию; обязателен для сценариев с `{{TYPE:issue}}` (`incident-screen-required`, `incident-screen-stale`): id или точное имя существующего стандартного (не подзадача) типа задач, например `LIVE_ISSUE_TYPE=10000`. Тип читается из Jira при `--check` и при запуске, до setup и моделей; неизвестный, неоднозначный, подзадача или имя, которое носит ещё один тип — ошибка, подмены и нового глобального типа нет. Значение пишется в заголовок `conversation.md` (`TYPE:issue = …`) и используется `--regrade` |
| `DIALOG_ASSISTANT_MODEL` | модель omp по умолчанию |
| `DIALOG_USER_MODEL` | модель omp по умолчанию |
| `DIALOG_THINKING` | не задано |

Модели вызываются через `omp` (`OMP_EXECUTABLE` переопределяет путь). Каждый ход — платный вызов модели.

Код выхода: `0` — диалог завершён (DONE, GIVE_UP или лимит ходов) и обязательные проверки прошли (advisory-наблюдения допустимы), `1` — ошибка во время диалога (транскрипт сохранён), `2` — диалог не запустился/неверные аргументы, `3` — диалог завершён, но contract-проверка не прошла.

## Обязательный комментарий на переходе (comment_required)

Новые сценарии используют тот же `run.mjs`, но **read-only setup/hooks**. Предварительная подготовка выполняется оператором через MCP: preview → таблица последствий → собственное подтверждение человека именно этого preview → `start_operation` → конечный `get_operation`. Автоматический `setup.apply` здесь не используется. Все объекты — свежие `LOCAL-005-...` / `L005...`; setup проверяет реальные identifiers, consumers и принадлежность scope, не переиспользует исторические id. Запуск ассистента — платные вызовы модели; `--human-user` заменяет только агента-пользователя.

| Сценарий | Обязательное фактическое evidence |
| --- | --- |
| `workflow-comment-l1-inactive` (L1) | XML до/после, три аргумента по порядку, новый UUID, соседние правила/аргументы/порядок сохранены; readback и повторный UNCHANGED без записи |
| `workflow-comment-l2-active-ui` (L2) | Один план `configure_workflow` (SET экрана: meta + view вместе с `comment_required`); ACTIVE не меняется до отдельной публикации; UI-диалог, пустой/старый комментарий не пропускают, новый непустой сохраняется; whitespace — только наблюдение; ScriptRunner open/save + XML + COMMENT_REQUIRED |
| `workflow-comment-l3-legacy-repair` (L3) | Legacy meta без view: problem и отказ без planId, включая уже точный валидатор; один план `configure_workflow` (SET того же id + `comment_required`) исправляет view в diff, сохраняет соседей и не дублирует валидатор; для перехода без валидатора — экранный SET; после исправления UNCHANGED либо WOULD_CHANGE |
| `workflow-comment-l4-stale-screen` (L4) | Отдельно подтверждённый CLEAR только тестового перехода; старый validator apply → PREVIEW_STALE; XML после stale равен состоянию после внешнего CLEAR |
| `workflow-comment-l5-screen-integration` (L5) | Сохранённые XML/readback `configure_workflow` create с экраном и existing.changes ADD/SET/replace/CLEAR, CLEAR не трогает иной view, no-screen и чужие атрибуты неизменны; детерминированная проверка без отдельного модельного диалога на каждый вариант |

### Подготовка и artifacts

`LOCAL005_EVIDENCE_FILE` — путь к JSON manifest: `schemaVersion:1`, `scenario:"L1"..."L5"`, реальные `workflow`, `actionId`, `screenIds`, для активного workflow — `project`. Setup возвращает проверенные identifiers и начальные snapshots. Подготовка существующих объектов должна иметь `setupTrace` / `setupConversation`. Любой artifact задаётся как `{ "path": "...", "sha256": "<SHA-256 файла>" }`; относительные пути считаются от manifest. Runner сохраняет manifest в run с абсолютными artifact paths для воспроизводимого `--regrade`.

`steps[]`: `name`, `beforeXml`, `afterXml`, `previewSeq`, `operationId`. Имена: L1 `comment-add`; L2 `comment-add` (один план: SET экрана + комментарий); L3 `same-id-repair`, `missing-comment-repair` (два отдельно подтверждённых плана `configure_workflow` для веток с/без валидатора; после них UNCHANGED и WOULD_CHANGE соответственно, без повторного apply); L4 `external-screen-clear`; L5 `create-with-screen`, `add-with-screen`, `set`, `replace`, `clear`, `other-view-clear`. L5 также задаёт реальные `createdWorkflow` (новый workflow для create-with-screen), `createActionId` (его переход), `addActionId`, `otherViewActionId`, `noScreenActionId` (переходы основного workflow с разрешённой legacy fixture). Consumers обоих workflow проверяются независимо.

`ui[]` для L2: `kind` (`dialog`, `empty`, `old-comment`, `nonempty`, `whitespace`, `no-screen`, `sr-open-save`), `workflow`, `actionId`, `issueKey`, `page` (сохранённый фактический HTML/DOM), по проверке — `beforeIssue` / `afterIssue` (реальные JSON задачи: `fields.status.id`, `fields.comment.comments`), `beforeXml` / `afterXml`, `readback`. `sr-open-save` дополнительно требует `openPage` редактора до сохранения. Verifier проверяет содержимое и связи artifacts с переходом/задачей/trace, а не булевы отметки «проверено». Эти сценарии выбирают отдельные свежие задачи для empty/old-comment/nonempty/whitespace/no-screen; verifier запрещает молчаливое повторное использование задачи без доказанного восстановления. Для `no-screen` обязателен отдельный реальный `noScreenActionId` на разрешённой fixture. Для L3 обязателен `missingCommentActionId` второй legacy-ветки без валидатора.

Во время интерактивного прогона оператор дополняет manifest реальными step/UI artifacts и их SHA-256; runner перечитывает файл перед финальным verifier и сохраняет итоговую копию. Обновление manifest не может менять подготовленные workflow/action/screen scope. Если сохранение валидатора UI создаёт draft, он фиксируется чтением; автоматические discard/публикация не разрешаются.

Для независимых UI-наблюдений реализованная ветка harness требует разные задачи; автоматическое восстановление статуса не используется. После сохранения ScriptRunner записывается фактический FIELD_FUNCTION_ID: сохранение или регенерация id — наблюдение, не обещанная семантика. Новый UUID обязателен именно при первоначальном добавлении Control; соседние существующие IDs сохраняются.

Legacy meta без view и иной view нельзя создавать прямой записью БД, XML или обходной REST-мутацией. Если нет воспроизводимой разрешённой fixture — L3/соответствующая ветка L5 **BLOCKED**, а не PASS. Отсутствующие XML/UI artifacts — **NOT_VERIFIED/BLOCKED**, `ok:false`. Полное равенство XML не требуется: сравниваются неизменённые структурные фрагменты, исключая изменяемые Jira служебные workflow metadata.

### Команды из корня проекта

```powershell
$env:LOCAL005_EVIDENCE_FILE = 'path/to/evidence.json'
node evals/live/run.mjs workflow-comment-l1-inactive --check
node evals/live/run.mjs workflow-comment-l1-inactive --setup-only
# Платный запуск ассистента (ответы вводит человек):
node evals/live/run.mjs workflow-comment-l1-inactive --human-user --max-turns 15
# Read-only перепроверка сохранённого run, без вызова моделей (также путь для L5):
node evals/live/run.mjs --regrade evals/live/scenarios/workflow-comment-l5-screen-integration/runs/<run>
```

Нужны локальная Jira, актуальная сборка Control, ScriptRunner 9.46.0, `JIRA_TOKEN` администратора; переменные подключения — в разделе «Запуск». В `--human-user` ответы вводит человек; `/done` завершает, `/stop` останавливает. Модель пользователя не запускается; каждое изменение существующей конфигурации всё равно требует своей показанной таблицы и отдельного «да». `--check` не выполняет setup, verifier или UI; `--setup-only` только читает fixture и не означает прохождение сценария. Для ручного run, перепроверяемого `--regrade`, сохраняются стандартные `conversation.md` (заголовок `- Пользователь: human`), `tool-trace.json`, `setup.json`, `lifecycle.json` и `evidence.json`; реальные подтверждающие сообщения остаются в conversation.

Бесплатные verifier/CLI smoke используют синтетические artifacts и fake Jira, это не online-проверки.

## configure_workflow: создание, правка, устаревший план

`configure_workflow` (цель `create` или `existing`, правила и экраны переходов в одном preview) — единственный инструмент создания и правки workflow; прежних `create_workflow`, `configure_workflow_validators` и `configure_workflow_conditions` в каталоге нет. Подготовка workflow в live-сценариях идёт через `apply('configure_workflow', {create})` с ключом на каждом переходе; сценарии и verifier'ы читают `existing.*` и `transitionRules[]`. Три автономных сценария (без оператора, пользователя играет модель; `setup.mjs` создаёт только свежие объекты `LOCAL-008-…`; запуск по одному):

| Сценарий | Что проверяет `verify.mjs` |
| --- | --- |
| `workflow-configure-create` | Один preview (create, существующие статусы, один local-переход Start, `comment_required` + `required_fields[description]` + AND(group, project_role) + один новый экран по `screenKey`), одна таблица и один вопрос, один `start_operation` по этому planId, нет вызовов удалённых tools и `edit_workflow`/`create_screen`; итог из Jira (readers и экспортированный дескриптор): оба validators, оба условия прямыми потомками одного AND, один экран с `view="fieldscreen"`; формулировки «все условия», «любая из ролей», сужение, наблюдение о пустой группе; прочитанная конфигурация показана |
| `workflow-configure-existing` | Неактивный workflow с посторонними validators/условиями (local, global `fromAny` и переход с существующим экраном): посторонние правила первыми и неизменны, добавленное в конце, пересекающееся `required_fields` покрыто объединением без дубля (Finish) либо отдельным validator только с недостающим полем (Start work), один новый экран на два перехода с view, одноимённый существующий экран не изменён и не переиспользован, нативный API принял дубль имени (иначе FAIL с фиксацией факта), предупреждение о совпадении имени в preview и таблице, дескриптор равен прежнему кроме добавленного, один preview/один apply |
| `workflow-configure-stale` | Hook после первого preview штатным действием админки добавляет статус; apply старого плана → `PREVIEW_STALE` без записи (workflow равен внешнему изменению, нового экрана и правил нет), старый план не применяется повторно, перечитан workflow, построен новый preview с новой revision и другим planId, подтверждение запрошено заново; пользователь новый план не подтверждает |

Общие проверки трассы — `checks.mjs`; общие helper'ы — `configure-common.mjs`. Офлайн-проверка самих verifier'ов на синтетическом состоянии Jira и трассе (хорошие и дефектные диалоги, без Jira и моделей): `node --test evals/offline/configure-live-verifiers.test.mjs`. Не покрыто live (только Java-тесты): корневое OR/неизвестный корень условий, ScriptRunner отсутствует/другой версии, не-администратор, частичные сбои, `AMBIGUOUS_SCREEN` и прочие отказные ветки; новые статусы покрыты сценарием `workflow-configure-new-statuses` (ниже).

Результаты этих трёх сценариев явно разделены на `contract` и `advisory`: состояние Jira/XML, параметры и ответы tools, наличие чтения после apply и отдельное подтверждение конкретного preview — обязательный контракт. Таблицы, формулировки предупреждений и финального ответа — advisory-наблюдения, не доказательство смысла текста и не причина FAIL. Общая проверка подтверждения у этих сценариев не зависит от Markdown-таблицы; у остальных сценариев прежняя строгая оценка сохраняется.

`FIELD_FUNCTION_ID` проверяется как canonical UUID независимо от версии генерации: планировщик `configure_workflow` записывает стабильные name-based UUID (v3), поэтому тот же canonical-паттерн (`CANONICAL_UUID`) используют все проверки того, что записал `configure_workflow` — три новых сценария, `additionChecks` L1–L5 и `comment-required-auto`, роль в `workflow-condition-role-inactive`; точные имена/число аргументов, script/class и значения условий остаются обязательными. Прежний UUIDv4 остаётся только дефолтом `exactComment`/`exactRole` для идентификаторов, которые не пишет `configure_workflow`. Эвристики AND/any-role/сужения и missing-field warning сохранены для наблюдений о формулировках, а не как contract. Stale setup отдельно сохраняет штатный INITIAL PermissionValidator; verifier проверяет, что он неизменен, а отсутствие добавленных правил/экранов требует только на заданных рабочих переходах (Start work, Finish), не ошибочно на Create.

```powershell
node evals/live/run.mjs workflow-configure-create --check
node evals/live/run.mjs workflow-configure-create --assistant-model anthropic/claude-sonnet-5-5 --user-model anthropic/claude-haiku-5-5 --max-turns 15
# Аналогичные флаги доступны для workflow-configure-existing и workflow-configure-stale.
# Платные вызовы моделей; создаёт объекты в Jira без очистки. Запускать по одному.
```

## configure_workflow с новыми глобальными статусами

`workflow-configure-new-statuses` — ассистент одним планом `configure_workflow` (цель `create`) создаёт workflow из трёх **новых** глобальных статусов разных категорий (TO_DO / IN_PROGRESS / DONE) с validator `required_fields[assignee]` на первом переходе и condition `project_role` Administrators + resolution на втором. `setup.mjs` ничего не создаёт: проверяет существующие роль Administrators, поле `assignee`, Resolution и свежесть имён `L010-<RUN>-…`. Одно подтверждение пользователя, один `start_operation`.

- `contract` (`verify.mjs`, только состояние Jira, трасса и поля ответов tools): три статуса с префиксом прогона, имена и категории как в запросе; workflow из трёх шагов и двух переходов с запрошенными именами, validator, condition и resolution читаются из Jira; один preview → подтверждение → один `start_operation`, `get_operation` `COMPLETED`, три шага `STATUS` `APPLIED/MATCH`, нет `PARTIAL/MISMATCH`; в `diff` preview строки `ADD_VALIDATOR` (`required_fields`, `assignee`) и `ADD_CONDITION`.
- `advisory`: одна таблица с объектами и названный глобальный эффект новых статусов.
- Офлайн-проверка verifier'а на синтетическом состоянии (без Jira и моделей): `node --test evals/offline/configure-new-statuses-verifier.test.mjs`.

```powershell
node evals/live/run.mjs workflow-configure-new-statuses --check
node evals/live/run.mjs workflow-configure-new-statuses --assistant-model anthropic/claude-sonnet-5-5 --user-model anthropic/claude-haiku-5-5 --max-turns 15
# Прогон создаёт глобальные статусы и workflow L010-<RUN>-…; очистки нет. Не запускать параллельно с другими сценариями.
```

## configure_workflow: структура существующего workflow и draft активного

Два автономных сценария создают setup-ом только свежие Control-объекты `L012-<RUN>`: workflow и глобальные статусы; для draft также экран, отдельный проект и его собственную workflow-схему. Существующие Resolution и lead читаются из каталогов, не меняются. Setup использует runner `apply` (preview → start_operation → terminal); ручных manifests, REST/XML/БД/UI fixtures и hooks нет. Очистки нет.

| Сценарий | Contract |
| --- | --- |
| `workflow-configure-existing-structure` | Один existing preview с ADD_TRANSITION/transitionKey, новым экраном через screenKey и required_fields assignee, плюс rename существующего перехода. Control readers проверяют source/target/Resolution, экран/поля/validator, сохранность соседей и фактические ID из результата. |
| `workflow-configure-existing-draft` | Setup назначает свежий workflow только свежему проекту. Диалог меняет имя и делает screen CLEAR через changes; terminal DRAFT_SAVED, обе правки в draft, active и все назначения проекта неизменны. Пустой проект не доказывает отсутствие issue migration. |

Оба проверяют ACTIVE/revision → один preview → отдельное подтверждение → один configure start_operation → terminal COMPLETED и доменный успех; другие мутации, edit_workflow, публикация и назначения в диалоге запрещены. Общие helpers переиспользуются; оформление таблицы/формулировок — только advisory, свободная семантика regex не оценивается. Оба сценария входят в presentation-classification список runner, чтобы формат таблицы не становился contract.

```powershell
node evals/live/run.mjs workflow-configure-existing-structure --check
node evals/live/run.mjs workflow-configure-existing-draft --check
# Платные вызовы моделей, создают объекты без очистки; запускать по одному:
node evals/live/run.mjs workflow-configure-existing-structure --assistant-model anthropic/claude-sonnet-5-5 --user-model anthropic/claude-haiku-5-5 --max-turns 20
node evals/live/run.mjs workflow-configure-existing-draft --assistant-model anthropic/claude-sonnet-5-5 --user-model anthropic/claude-haiku-5-5 --max-turns 20
```

## Подтверждение подготовки моделью (simulated-user)

Opt-in `setup.mjs` экспортирует `export const confirmation = "simulated-user"` и обычный default setup. Для `workflow-configure-existing-new-status-*` этот режим обязателен: отсутствие метаданных останавливает даже `--check`, fallback к старому auto-apply нет.

`setup.apply` получает настоящий preview, показывает полное domain-содержимое (включая diff, consumers, global/shared effects, warnings и assumptions) без planId и задаёт один вопрос. Tool-less пользовательская модель из `--user-model`/`DIALOG_USER_MODEL` отвечает собственным JSON `{decision:"APPROVE"|"REJECT",message:string}`; без явно настроенной доступной модели, при отказе, неверном ответе, timeout или сбое evidence запись запрещена. Разрешение модели ограничено свежими `L013-<runId>-` объектами (project keys с L013), без issues, чужих данных, cleanup и rollback; active assignment — только свежему пустому fixture-проекту.

Авторизация подготовки различает создание fixture и ссылки без изменения каталожных объектов. Разрешены новый standalone-экран со своим layout без назначений и существующий активный пользователь как lead нового fixture-проекта: это не изменение пользователя. Ссылки на существующие типы/поисковики полей, системные поля, Resolution и шаблоны правил/ScriptRunner не разрешают менять их глобальные настройки. Глобальная видимость нового статуса/поля сама по себе не означает изменение чужой конфигурации; context нового поля остаётся только в свежем проекте текущего run. Экраны и workflow связываются только внутри текущего fixture; назначение workflow — свежему пустому проекту через новую приватную схему, без изменения общей схемы. Объекты прежних run, чужая конфигурация, публикация и миграция issues остаются вне scope. Это границы решения модели, не автоодобрение: каждый preview требует отдельного собственного APPROVE, REJECT останавливает всю подготовку.

`create_project` с `action:"create"` и `templateKey` Project Management (общий `projectManagement()` из `evals/live/project-templates.mjs`) в template-mode создаёт BUSINESS-проект по стандартному Project Management template: новый проект может ссылаться на существующие общие схемы. Это не обещание приватных копий схем или закрытой видимости проекта. Допустимы ссылки нового проекта, но не изменение определений/маппингов общих схем или назначений других проектов. Приватная workflow scheme нужна при последующем отдельно подтверждённом `assign_workflow`; его preview должен подтверждать отсутствие затрагиваемых issues.

Tool-less модель получает точный запрос, текущий preview и terminal results ранее подтверждённых операций **только этого run** (`completedSetupOperations`), чтобы распознавать fixture ID. Эти результаты сохраняются также в conversation и не заменяют новое согласие. Контекст объясняет штатный INITIAL Create validator нового workflow: native `PermissionValidator` проверяет Create Issue, хотя reader показывает его как OTHER. Это не разрешение произвольных неизвестных правил в других переходах/workflow; настройка перехода не означает его исполнение.

Каталог run и evidence создаются **до setup** и сохраняются при ошибке: `setup-conversation.md` содержит показанные последствия и реальные replies; `setup-trace.json` — каждый preview/approval/start/poll/terminal, canonical payload hash, plan/operation binding, timestamps и полные ответы; `setup.json` — только завершённый результат подготовки. Approval и intent сохраняются до start. Основной диалог и его лимит ходов начинаются только после успешного setup.

Протокол fail-closed: stale/FAILED/PARTIAL/UNKNOWN останавливают setup, без нового preview, apply или rollback. Потеря ответа start допускает не больше одного идентичного recovery-start того же плана; известный operationId восстанавливается get_operation. Каждый успешный native эффект остаётся в evidence. `tool/probe` в этом режиме допускают только каталожные get_/list_/plan_ инструменты; REST/adminPage — только GET. Verify также read-only.

`--setup-only` использует этот же модельный диалог и evidence, а не обход согласия. `--check` не выполняет setup и не вызывает модели. `--regrade` читает сохранённые setup trace/conversation, передаёт их как `setupTrace`/`setupConversation` в verify и проверяет matching preview→модельное APPROVE→один start (либо единственное идентичное recovery), terminal и отсутствие смешения с основным планом. Эти проверки — contract; оформление таблицы и текста — advisory. Обычный setup API (раздел «Подготовка») остаётся явно неинтерактивным и не считается задним числом подтверждённым.

### Новый статус в существующем workflow (existing.newStatus)

`workflow-configure-existing-new-status-inactive`: подтверждённый setup создаёт свежие `L013-<runId>-` Todo→Done, comment_required, контрольный required_fields summary, глобальное текстовое поле с суффиксом Frosty Dune QG5 и seed screen. Основной единый план вставляет Делоем, переносит источник Finish, добавляет Enter work, priority/customfield validators и общий новый экран. Verifier читает native status/step/action/screen IDs, правила, соседей и отсутствие bypass.

Вариант для draft активного workflow live-сценарием не покрыт: active→draft и повторное использование проверяются только Java/native-тестами.

Setup экспортирует `confirmation = "simulated-user"`; каждый preview подтверждается отдельно через opt-in runner. Дополнительные hooks и ручные manifests не нужны. Основная трасса не допускает иных мутаций, publish/assignment/migration или вспомогательных workflow. Setup и основная операция проверяются раздельно. State/tool/protocol — contract; presentation — advisory, без regex-проверки смысла текста. Cleanup/rollback нет.

Проверка сохранности сопоставляет переходы по уникальному `actionId`: допускается только перестановка внешнего списка reader. Проверяется точный состав прежних ID плюс ожидаемых новых; пропавшие, лишние и дублирующиеся ID дают FAIL. Все прежние свойства сравниваются строго, включая порядок вложенных from/to, validators/fieldIds и conditions; разрешённые изменения Finish остаются прежними. Офлайн-регрессии: `node --test evals/live/existing-new-status-common.test.mjs`. Офлайн-проверка компаратора не заменяет live-прогон.

```powershell
node evals/live/run.mjs workflow-configure-existing-new-status-inactive --check
# Платные вызовы моделей; по одному:
node evals/live/run.mjs workflow-configure-existing-new-status-inactive --assistant-model anthropic/claude-sonnet-5-5 --user-model anthropic/claude-haiku-5-5 --max-turns 20
```

`--check` не выполняет setup, не вызывает модели и не пишет в Jira; это не прохождение сценария. Неизвестные validators/UI, live issues, конфликты и сбои/переподключение остаются вне этих диалогов.

## Строки существующей Issue Type Screen Scheme

Два автономных сценария для `set_issue_type_screen_scheme_mappings`; общий код — `itss-mappings-common.mjs`, привязка проектов к DEFAULT ITSS — `itss-bind.mjs`, офлайн-проверка — `node --test evals/live/itss-mappings-verifier.test.mjs evals/live/itss-mappings-common.test.mjs evals/live/itss-bind.test.mjs`. Подготовка (`confirmation = "simulated-user"`, как в разделе «Подтверждение подготовки моделью», с собственным `confirmationScope`) создаёт только свежие `L016-<runId>-` типы, экраны и проекты `L016…`; GET-страницы админки читают таблицу строк и связанные проекты. Свободный текст регулярками не оценивается; запрещены любые записи кроме инструмента, `start_operation` и одной описанной ниже привязки свежих проектов сценария 2.

| Сценарий | Что делает | contract | advisory |
| --- | --- | --- | --- |
| `itss-mappings-private` | свежий проект с приватной ITSS: REPLACE типа A S1→S2 и REMOVE типа B одним планом | один план и один start; тот же id ITSS; строки превью, перечитанные строки результата и страница админки совпадают; новых ITSS/Screen Scheme нет; связь проекта прежняя; N=1, M=1 | таблица называет N и M |
| `itss-mappings-shared-default` | временная строка свежего типа в общей DEFAULT ITSS: ADD, затем REMOVE (два плана) | N = числу проектов DEFAULT ITSS по странице админки, M=2; setup подтвердил привязку A и B к DEFAULT ITSS (`prepared.binding`: список проектов DEFAULT ITSS до + A + B, перечитан после каждой привязки); hook до хода пользователя останавливает диалог (код 1), если последнее превью — другой ITSS или строки, кроме свежего типа; наблюдение hook после ADD и после REMOVE; таблица после REMOVE = исходной; id DEFAULT ITSS и связи прежние; копий нет | формулировка N/M и обрезки, одна таблица |

**Привязка свежих проектов к DEFAULT ITSS ** Ни один инструмент Control не привязывает проект к существующей ITSS, а шаблон Project Management создаёт проект с собственной ITSS. Поэтому setup второго сценария после `create_project` (Control) привязывает проекты `L016A<RUN>` и `L016B<RUN>` к Default Issue Type Screen Scheme (id 1) формой админки Jira «Select Issue Type Screen Scheme»: GET `SelectIssueTypeScreenScheme!default.jspa?projectId=<id>` (токен `atl_token`, cookies сессии, поле `projectId`, опция схемы 1) → POST `SelectIssueTypeScreenScheme.jspa` (`projectId`, `schemeId=1`, `atl_token`, `Associate`) → GET списка схем: проекты DEFAULT ITSS должны быть «прежние + этот проект», иначе `STOP_CONDITION_5`. Токен — загрузчика (`JIRA_TOKEN`), хост только локальный. Это **единственное исключение** из «админка — только GET» и узкий allowlist, а не общий write REST (`itss-bind.mjs`): (1) цель — проект, чьё создание (`create_project` → `CREATED`, тот же `projectId`) наблюдал runner в этом прогоне, с ключом `L016A|L016B<RUN>`; прочие проекты, в т.ч. другие `L016…`, отказ до любого запроса; (2) схема только id 1; (3) транспорт отправляет лишь GET списка схем / формы одного числового проекта и этот POST с ровно четырьмя полями — иное отказ до запроса; (4) `bindToDefaultItss` runner даёт только setup сценария `itss-mappings-shared-default`; `adminPage` подтверждённого setup, hooks, verify и диалог остаются GET-only; (5) удалений нет, прежние приватные ITSS A и B остаются без проектов. Офлайн-проверка: `node --test --test-timeout=60000 evals/live/itss-bind.test.mjs`.

Setup второго сценария останавливается с `STOP_CONDITION_5`, если привязка не подтверждена: форма, POST, список проектов DEFAULT ITSS или `get_project_screens` (проект не на DEFAULT ITSS). Сбой между ADD и REMOVE оставляет временную строку (автоочистки нет). Оба имени добавлены в список сценариев, где оформление таблицы — advisory (`run.mjs`).

```powershell
node evals/live/run.mjs itss-mappings-private --check
node evals/live/run.mjs itss-mappings-shared-default --check
# Запуск по одному: --assistant-model <модель ассистента> --user-model <модель пользователя> --max-turns 20
```

## Строки существующей Screen Scheme на месте

Два автономных сценария для `set_screen_scheme_operations`; общий код — `screen-scheme-ops-common.mjs` (`itssIds`, `screenSchemeIds`, `itssProjectKeys`, `itssTable` берутся из `itss-mappings-common.mjs`), офлайн-проверка — `node --test evals/live/screen-scheme-ops-verifier.test.mjs`. Подготовка (`confirmation = "simulated-user"`, собственный `confirmationScope` каждого сценария) создаёт только свежие `L017-<runId>-` экраны (`create_screen`, поля непусты) и проекты `L017…`; приватные Issue Type Screen Scheme и Screen Scheme свежих проектов получаются штатным `set_project_operation_screens` (`allIssueTypes`: явные строки CREATE и EDIT в схеме). Строки Screen Scheme читаются `get_project_screens` (explicit rows), ITSS — GET страницами админки; admin POST нет. Свободный текст регулярками не оценивается; запрещены любые записи, кроме инструмента и `start_operation` (в setup — кроме перечисленного).

| Сценарий | Что делает | contract | advisory |
| --- | --- | --- | --- |
| `screen-scheme-ops-private` | приватная схема свежего проекта: одним планом REPLACE CREATE на экран без Summary и REMOVE EDIT | один план и один start (`planId` последнего превью до «да»); тот же id схемы; строки превью, перечитанные строки результата и `get_project_screens` совпадают; в превью `REQUIRED_FIELDS_MISSING_ON_CREATE`, среди полей Summary; новых ITSS/Screen Scheme нет; связь проекта прежняя; setup подтвердил, что Summary обязательно в field configuration | таблица называет N/M и предупреждение |
| `screen-scheme-ops-shared` | схема S общая для проектов A и B (разные приватные ITSS): REPLACE EDIT на свежий экран E, затем возврат прежнего (два плана) | N=2, M=2 в обоих превью; id S прежний; hook после каждого apply записывает EDIT всех типов A и B и таблицу S (E после первого, прежний после возврата); таблица S после возврата равна исходной; ITSS и их строки не менялись; копий нет; setup подтвердил общую схему перечиткой | формулировка N/M, одна таблица |

**Общая схема без admin POST.** Setup второго сценария один раз вызывает `set_issue_type_screen_scheme_mappings` (REPLACE DEFAULT-строки частной ITSS проекта B на Screen Scheme проекта A; `sharedScope` разрешает только этот вызов) и перечитывает: ITSS обоих проектов остались частными и прежними, S эффективна для EDIT всех типов обоих проектов, `otherProjects` схемы = 1. Иначе `STOP_CONDITION_5`; обход (admin POST) требует отдельного согласия. Hook обоих сценариев только читает и останавливает диалог (код 1) до хода пользователя, если последнее превью — другая схема или строки вне задания. Сбой между изменением и возвратом оставляет изменённую строку только в свежей схеме (автоочистки нет). Оба имени добавлены в список сценариев, где оформление таблицы — advisory (`run.mjs`).

```powershell
node evals/live/run.mjs screen-scheme-ops-private --check
node evals/live/run.mjs screen-scheme-ops-shared --check
# Запуск по одному: --assistant-model <модель ассистента> --user-model <модель пользователя> --max-turns 20 (shared: 24)
```

## Связь проекта с существующей Issue Type Screen Scheme

Один автономный сценарий `itss-assign-shared` для `assign_issue_type_screen_scheme`; код — `itss-assign-common.mjs` (читатели `itssIds`, `screenSchemeIds`, `itssProjectKeys`, `itssTable` берутся из `itss-mappings-common.mjs`, `normalizeOperation` — из `screen-scheme-ops-common.mjs`), офлайн-проверка — `node --test evals/live/itss-assign-verifier.test.mjs`. Подготовка (`confirmation = "simulated-user"`, собственный `confirmationScope`) создаёт только свежие `L018-<runId>-` экраны (`create_screen`) и проекты `L018A…`/`L018B…`; приватную ITSS каждому проекту даёт штатный `set_project_operation_screens` (`allIssueTypes`, CREATE/EDIT/VIEW = экран проекта). Ни admin POST, ни `set_issue_type_screen_scheme_mappings` не используются. Свободный текст регулярками не оценивается.

| Сценарий | Что делает | contract | advisory |
| --- | --- | --- | --- |
| `itss-assign-shared` | свежий проект B (приватная ITSS) → ITSS проекта A (одно превью, «да», один `start_operation`); затем возврат B на прежнюю ITSS (второй план) | два `start_operation` по planId последнего превью, других записей нет; превью 1: WOULD_CHANGE, проект B, ITSS B → ITSS A (id, имя), по каждому типу задач Screen Scheme и экраны CREATE/EDIT/VIEW до → после, CHANGE; `SHARED_TARGET` (проект A) и `ITSS_ORPHANED`; превью 2: обратное, без обоих предупреждений; apply: COMPLETED / CHANGED, `readBack`, перечитанная ITSS = целевая; hook после каждого apply: `get_project_screens` A и B и страницы проектов обеих ITSS (после переключения B = A, A не менялась, у A [A, B], у прежней ITSS B пусто; после возврата всё как до); в конце связи и строки ITSS прежние, числа ITSS и Screen Scheme не менялись | одна таблица с проектом B и обеими схемами, таблица показывает Screen Scheme и экраны |

Hook только читает и останавливает диалог (код 1) до хода пользователя, если последнее превью — не проект B или не та схема. Setup останавливается с `STOP_CONDITION_5`, если схема проекта общая/Default, схемы проектов совпадают или не покрывают одни и те же типы, либо страница админки показывает чужие проекты. Сбой между переключением и возвратом оставляет B на схеме A (автоочистки нет). Имя добавлено в список сценариев, где оформление таблицы — advisory (`run.mjs`).

```powershell
node evals/live/run.mjs itss-assign-shared --check
# Запуск по одному: --assistant-model <модель ассистента> --user-model <модель пользователя> --max-turns 30
```

## Копирование, правка и переименование workflow

`workflow-copy-edit-rename` проверяет последовательность копирования неактивного workflow, переименования Ship → Release и добавления Back to build (QA → Building с явным Resolution CLEAR) через `configure_workflow.existing.changes`, затем отдельного `rename_workflow` на новое имя копии. Исходник остаётся неизменным, назначений и публикаций нет.

Подготовка выбирает только существующие статусы TO_DO / IN_PROGRESS / IN_PROGRESS / DONE (предпочитает Open, Building, QA, Done) и существующую подходящую Resolution. Фактические имена передаются пользователю через `known`; новые глобальные статусы не создаются. Проверки сравнивают исходный снимок с итогом, граф копии и последовательность apply; допустимы один или два apply правок до переименования.

Все поддерживаемые structural setup/hooks используют `configure_workflow` с `existing:{workflow,version,expectedRevision,changes}`; revision читается непосредственно перед preview. Успех подтверждается терминальным результатом `UPDATED_INACTIVE`/`DRAFT_SAVED` и read-back, а не старым `UPDATED` или одним COMPLETED. Запрещённые legacy-вызовы остаются в negative trace fixtures и guards, но `edit_workflow` больше не вызывается как поддерживаемый инструмент. Новые сценарии или live-запуски при этой миграции существующих callers не добавляются.

```powershell
node evals/live/run.mjs workflow-copy-edit-rename --check
node evals/live/run.mjs workflow-copy-edit-rename --setup-only
$env:DIALOG_ASSISTANT_MODEL = 'anthropic/claude-sonnet-5-5'
$env:DIALOG_USER_MODEL = 'anthropic/claude-sonnet-5-5'
node evals/live/run.mjs workflow-copy-edit-rename --max-turns 15
```

## Раскладка экрана только в своём проекте

`screen-own-layout`: подготовка создаёт два проекта на одной общей нестандартной схеме экранов (стандартная схема Jira не трогается; второй проект привязывается штатной формой Jira) и три необязательных поля на экранах CREATE/EDIT/VIEW. Пользователь просит переставить и убрать поле на экране редактирования только своего проекта, не зная, что экраны общие. Проверяется, что ассистент сначала отделяет экраны проекта (`clone_project_screens`), затем ровно одним применением `edit_screen_layout` совмещает перенос f3 после Summary и снятие f2 на EDIT копии; соседний проект, CREATE/VIEW и общие экраны сравниваются со снимком подготовки.

```powershell
node evals/live/run.mjs screen-own-layout --setup-only
node evals/live/run.mjs screen-own-layout --max-turns 15
```

## Одна совмещённая правка собственной раскладки

`screen-layout-one-preview`: один проект с собственными разными экранами CREATE/EDIT/VIEW, порядок Summary, Description, f1, f2, f3; f4 существует, но отсутствует на экранах. Дословная первая просьба: «На экране редактирования {{KEY:main}} сейчас: Summary, Description, f1, f2, f3. Нужно: f3 после Summary, f2 убрать, добавить f4 в конец.» Проверка требует ровно одно превью и одно применение `edit_screen_layout` с переносом, снятием и добавлением; итог EDIT — Summary, f3, Description, f1, f4, CREATE/VIEW и назначения экранов неизменны. `--setup-only` создаёт настоящие объекты Jira, но не запускает LLM.

Подготовка намеренно использует точные имена f1–f4 из первой реплики. При повторном запуске уже существующие поля переиспользуются: их прежние проекты сохраняются, новый проект добавляется в существующий полный контекст всех типов задач. Поля не переводятся в глобальный контекст; если подходящего полного контекста нет, подготовка явно завершается ошибкой. Поэтому `--setup-only` может также расширить область существующих тестовых полей на новый проект.

```powershell
node evals/live/run.mjs screen-layout-one-preview --check
node evals/live/run.mjs screen-layout-one-preview --setup-only
node evals/live/run.mjs screen-layout-one-preview --max-turns 15
```

## Расширение контекста существующего поля

Две точки запуска с общей автоматической подготовкой:

- `field-context-extend` — расширить контекст «Закупки» на второй проект, сохранив оба типа, configId, три опции и значение по умолчанию «Б». Проверяются предупреждения о лишних сочетаниях и отсутствующем в целевом проекте типе Task, отдельное подтверждение перед записью.
- `field-context-extend-conflict` — то же исходное состояние, но второй проект уже занят другим контекстом поля. Ожидается `PROJECT_IN_OTHER_CONTEXT` с его номером, без planId и без записи.

Каждый запуск сам создаёт новые проекты со случайными ключами, поле «Поставщик {{RUN}}» и тип «Задача на закупку {{RUN}}». Это изолированные аналоги LOT/D2LOT; фиксированные проекты и ручная подготовка не нужны. Если стандартного типа Task нет, setup создаёт его. Новые типы глобальные и автоматически попадают в стандартную схему типов Jira; использовать только тестовую Jira. Очистки нет.

Общий `field-context-extend/setup.mjs` задаёт точные множества типов проектов, создаёт контексты и опции, устанавливает default «Б» через административную форму Jira и перечитывает его для проверки. Поле добавляется на собственные экраны целевого проекта (общие экраны предварительно копируются). Перед диалогом проверяются исходные настройки; их ID и значения сохраняются в `setup.json` прогона. Конфликтный сценарий использует тот же setup с включённым вторым контекстом.

```powershell
node evals/live/run.mjs field-context-extend --setup-only
node evals/live/run.mjs field-context-extend --max-turns 15
node evals/live/run.mjs field-context-extend-conflict --max-turns 15
```

Технические критерии приведены в сценарии: оператор сверяет `tool-trace.json`, затем проверяет конфигурацию и форму создания задачи в Jira. Runner не оценивает результат автоматически; `--check` проверяет загрузку сценария и подключение MCP, но не исходные данные и не UI. Переиндексацию в рамках сценария не запускать.

## Новый контекст select-поля со своими опциями

`field-context-create-select-options` — изолированный аналог D2LOT: подготовка создаёт проекты target/source и два поля («Система {{RUN}}» select с опциями КА Demo1/ШС/ТК и «Серийный номер {{RUN}}» text) с единственным контекстом source × Task. Пользователь просит оба поля для target × Bug, «Систему» — с опциями Заря R&D, Заря-С, Зарница R&D. Ожидаются два превью `create_field_context` (select с `options`, text без), одна таблица, одно «да», два apply. `verify.mjs` читает опции нового и старого контекста через штатную форму default Jira: новые — ровно три в порядке и без default, старые и scope source не изменены; `create_custom_fields`/`update_field_contexts` не применяются.

```powershell
node evals/live/run.mjs field-context-create-select-options --max-turns 15
```

## Экран и обязательность одним подтверждением

`incident-screen-required` — изолированный аналог ручного прогона D2LOT × Incident, проверка текстов `rules.md` и `customfields-tools.json`. Подготовка создаёт проекты target и source с единственным существующим стандартным типом, выбранным `LIVE_ISSUE_TYPE` (подстановка `{{TYPE:issue}}` в сценарии; не найден или неоднозначен — ошибка на `--check` и до setup, новые глобальные типы не создаются); у target собственные схема экранов с одним экраном на CREATE/EDIT/VIEW (Summary, Assignee, Reporter, «Тип испытаний», «Место обнаружения», «Кто обнаружил?», Description) и собственные схема и конфигурация полей. «Система» (select) и «Серийный номер» (text) существуют с единственным контекстом source × выбранный тип. Пользователь одной репликой просит четыре новых поля, два новых контекста существующих, порядок на экране, обязательность и убрать «Место обнаружения»; правок не вносит.

Проверяется: ровно два «да» (поля и контексты; экран и обязательность); превью `edit_screen_layout` и `set_field_required` в одном ходу, в одной таблице с одним вопросом, без новых превью после «да»; 0 вызовов `plan_field_screens`; нет повторных чтений без записи между ними (счётчики `get_field_configurations` и `list_custom_field_types` в detail); `start_operation(edit_screen_layout)` завершён раньше `start_operation(set_field_required)`, без `PREVIEW_STALE`; `create_field_context` для двух существующих полей и `create_custom_fields` для четырёх новых. Состояние: порядок экрана, required/optional в конфигурации target, контексты и опции, неизменность source. Порядок вызовов определяется по порядку завершения (`seq`), параллельный запуск двух `start_operation` трасса от последовательного не отличает. Нарушение P1–P5 — FAIL, ожидание под поведение модели не меняется. Подготовка вынесена в `prepare()` для сценария с намеренно упавшим экраном.

```powershell
$env:LIVE_ISSUE_TYPE = '10000'   # id или точное имя существующего стандартного типа задач этой Jira
node evals/live/run.mjs incident-screen-required --check
node evals/live/run.mjs incident-screen-required --setup-only
node evals/live/run.mjs incident-screen-required --max-turns 15
```

## Упавший экран

`incident-screen-stale` — ветка ошибки `incident-screen-required`: та же подготовка (`import { prepare }`), та же первая реплика и тот же запрос. Проверяет правило «если экран не применился — обязательность не применять, показать новый предпросмотр экрана и спросить заново» (`rules.md`, MULTI-STEP; `set_field_required`). Опасность: обязательные поля не на экране CREATE — задачу нельзя создать.

`hooks.mjs` срабатывает один раз — после хода, где есть превью `edit_screen_layout` и `set_field_required` и ещё нет `start_operation(edit_screen_layout)`. Штатным REST Jira (не инструментами плагина) он переносит Assignee в конец вкладки экрана (`POST /rest/api/2/screens/{id}/tabs/{tabId}/fields/assignee/move`, `{"position":"Last"}`) — как параллельная правка администратора. Fingerprint превью включает порядок и позиции полей экрана, поэтому применение возвращает `PREVIEW_STALE`. В `lifecycle.injection` пишутся ход, порядок до и после, HTTP-статус и required шести полей на момент hook.

Проверяется (`verify.mjs`): hook ровно один раз (H1); первый apply экрана — `PREVIEW_STALE` (F1); `set_field_required` не применялся раньше успешного экрана и в ходе со STALE, поля на момент hook необязательны (F2); в том же ходе новое превью экрана и вопрос (F3); после следующего «да» экран `EDITED`, затем обязательность, итоговые флаги как S2a (F4); Assignee остаётся последним (F5); итоговый порядок экрана (F6); ровно три «да»: поля и контексты; экран и обязательность (STALE); новый экран и обязательность (F7); S2b и S3–S5 берутся из `incident-screen-required/verify.mjs`.

```powershell
node evals/live/run.mjs incident-screen-stale --check
node evals/live/run.mjs incident-screen-stale --setup-only
node evals/live/run.mjs incident-screen-stale --max-turns 15
```

## Перенос между контекстами и устаревший preview

`field-context-transfer-stale` полностью автоматически готовит четыре новых проекта (аналоги LOT, LOTM, TSSLOT и LOTHW), два контекста поля «Поставщик {{RUN}}» с разными опциями и задачу со значением «А». Новые глобальные типы задач не создаются.

```powershell
node evals/live/run.mjs field-context-transfer-stale --max-turns 15
```

После первого показанного плана `hooks.mjs` добавляет четвёртый проект в «Основной» через штатный административный редактор Jira — между preview и ответом пользователя, без ручного вмешательства и без изменения MCP-ответов. Проверяется `PREVIEW_STALE`, сохранение конкурентного изменения и новое согласие. Ассистент выполняет прямой, затем отдельно запрошенный и подтверждённый обратный перенос; каждый — одним запросом для двух контекстов.

После обоих успешных переносов hook **реально переиндексирует только подготовленную задачу**, читает JQL, REST, HTML страницы задачи и формы настроек. Для HTML используется штатная сессия входа Jira 10 (`/rest/tsv/1.0/authenticate`); cookies остаются только в памяти. Это отличие от сценария расширения, где переиндексация не запускается. Задача после setup не перезаписывается; восстановление исходного ID опции после обратного переноса доказывает сохранность данных.

`verify.mjs` проверяет промежуточные снимки и протокол, включая конфликт добавления без снятия, оба `UPDATED`, сохранность configId/опций/default и видимость/поиск «А». Если конкретная версия Jira продолжает отображать или находить старую опцию в чужом контексте, соответствующий критерий остаётся FAIL: ожидание не подменяется наблюдённым поведением. Отсутствие вызова Java `updateFieldConfigScheme` при устаревании отдельно покрыто существующим `FieldContextScopeServiceImplTest#previewBecomesStaleOnPersistedOptionsDefaultScopeOrPluginChanges`; live-проверка доказывает отказ и неизменность наблюдаемого состояния.

## Новый проект по ТЗ

`safety-docs-new-project` — повтор живого запроса ручного прогона 28.10.2026 (ТЗ Safety Docs): создать проект с РП Pavel Ivanov, пять новых типов задач, точную копию workflow «PSAFE: base type» для всех типов, шесть новых полей, экраны и обязательность по типам. Первая реплика — ТЗ дословно; название проекта, типы и новые поля помечены `{{RUN}}`, ключ случайный, поэтому каждый прогон создаёт их заново. Подготовка создаёт, если их ещё нет, пользователя `p.ivanov` («Pavel Ivanov») и workflow «PSAFE: base type» (6 существующих статусов, 12 переходов), а также каждый раз новый соседний проект на стандартных схемах Jira с этим workflow и снимок его настроек.

Проверяется (`verify.mjs`): workflow скопирован `copy_workflow`, а не собран заново, все пять типов на копии с тем же графом, источник не изменён; в проекте ровно пять типов; ровно шесть полей с нужными типами, у каждого один контекст в проекте на нужные типы (8 опций у «Тип подзадачи», 15 у «Офиса»), без пустых контекстов; у каждого типа свои экраны CREATE/EDIT/VIEW со всеми полями ТЗ; обязательны только безусловные «Да» (условные, «Нет» и «Вложение» в «Бумажных документах» необязательны; «Вложение» в «Оплатах» не проверяется); схема и конфигурации полей проекта не общие; соседний проект и стандартные настройки не изменились.

```powershell
node evals/live/run.mjs safety-docs-new-project --check
node evals/live/run.mjs safety-docs-new-project --setup-only
node evals/live/run.mjs safety-docs-new-project --max-turns 30
```
