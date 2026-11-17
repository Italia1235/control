# create_field_context: подготовленные Live evals

Для `field-context-create-regression-approve` реализованы setup.mjs и verify.mjs. Первый live-запуск остановлен в preflight с кодом 2: точный тип Bug отсутствует либо имеет неверный признак subtask. Подготовка остановилась до записи, диалог не начался. Остальные 25 сценариев остаются BLOCKED_PREPARATION.

## Формат и основания

Используется существующий формат: отдельная папка с `scenario.md`, обязательный раздел `## Первая реплика`, свободные инструкции агенту-пользователю, DONE/GIVE_UP и штатные RUN/NAME/KEY placeholders. См. [правила live](../README.md), [runner](../run.mjs), [общие проверки](../checks.mjs), [пример подготовки](field-context-transfer-stale/setup.mjs), [пример hook](field-context-transfer-stale/hooks.mjs). Новый framework не создаётся.

`--check` подключается к MCP, `--setup-only` создаёт реальные объекты, `--regrade` читает Jira. Для regression-approve оператор разрешил подготовку и запуск; остальные сценарии пока не готовы. Runner не имеет блокировки по слову BLOCKED в Markdown.

Исходная регрессия изучена по локальному диалогу vas.txt от 27.10.2026. Вместо реальных проектов и числовых IDs используются свежие fixtures. Ошибочная стратегия исходного диалога — расширение старого scope с включением Task, затем клонирование экранов и снятие QA с общего экрана — является запрещённым поведением, а не предлагаемым fallback.

## Состояние локального контракта при подготовке

- На момент первоначальной подготовки create_field_context не был опубликован. Сейчас реализация присутствует; штатная проверка локальной Jira обнаружила control 1.1.4 и 43 инструмента.
- Есть частичный API: `FieldContextCreator.java`, `CreateFieldContextRequest.java`, `CreateFieldContextResponse.java`, комментарий в `ContextSchemeFactory.java`. DTO запроса: dryRun/planId, fieldId, name/description, allProjects/projects, allIssueTypes/issueTypes, options (только явный список для встроенных select-типов, обязателен для них). defaults/copyFrom из создания полей не переносим.
- В DTO ответа перечислены WOULD_CREATE, CREATED, PARTIAL, FAILED и ошибки, включая PROJECT_IN_OTHER_CONTEXT, GLOBAL_CONTEXT_EXISTS, UNSUPPORTED_FIELD_TYPE, FIELD_LOCKED, PREVIEW_STALE. Это доступная спецификация, а не доказательство работающего сервиса. settings/effectiveChange/conflict/scope/actual — Map: точные вложенные ключи, значения и предупреждения не закреплены в сценариях. Не выдумываем JSON ответов или результаты запуска.
- get_field_contexts: одинаковое имя требует выбора пользователем ID из всех типизированных кандидатов (AMBIGUOUS_FIELD), даже если пользователь уже назвал тип. projectsComplete=false требует дочитать конкретный contextId; UNKNOWN не отсутствие.
- update_field_contexts меняет только существующие контексты и требует обе полные итоговые оси. Он не создаёт контекст; общий scope — декартово произведение. Сервис сохраняет config/options/default и проверяет fingerprint перед записью.
- create_custom_fields создаёт поле, а для совпадающих имени/типа возвращает EXISTS без изменения его контекстов/настроек. Это не обход отсутствующего create_field_context.
- Изучены локальные `rules.md`, `rollback-tools.json`, `OperationRunner.java`, MCP-запрет прямого apply: preview → показ последствий → отдельное согласие → один start_operation. Только dryRun=false/planId в arguments. Нетерминальные состояния требуют get_operation; потерянный start без ID нельзя безопасно повторять.

Перед будущим запуском повторно сверить опубликованный контракт и реализацию: локальный API может продолжать меняться. Отсутствие инструмента означает честное сообщение о недоступности без опасного обхода; это может пройти safety-проверку, но не feature acceptance.

## Индекс

| ID (суффикс field-context-create-) | Диалог / проверка |
|---|---|
| [regression-approve](field-context-create-regression-approve/scenario.md) | A: согласие, один новый контекст, Task исключён |
| [regression-decline](field-context-create-regression-decline/scenario.md) | B: отказ, записи нет |
| [regression-change-types](field-context-create-regression-change-types/scenario.md) | C: новый набор типов, новый preview и новое да |
| [regression-question](field-context-create-regression-question/scenario.md) | D: вопрос о клонировании не подтверждает запись |
| [shared-existing](field-context-create-shared-existing/scenario.md) | Явно общий контекст → update_field_contexts |
| [separate-existing](field-context-create-separate-existing/scenario.md) | Отдельный контекст → create_field_context, не новое поле |
| [project-conflict](field-context-create-project-conflict/scenario.md) | Занятый проект, без автоматического сужения |
| [second-global](field-context-create-second-global/scenario.md) | Второй global |
| [global-project](field-context-create-global-project/scenario.md) | Project-specific рядом с global, effective config и данные |
| [options-defaults](field-context-create-options-defaults/scenario.md) | Не обещать копирование настроек |
| [restricted-type](field-context-create-restricted-type/scenario.md) | Реальная ошибка/предупреждение ограниченного типа |
| [missing-project](field-context-create-missing-project/scenario.md) | Не указан проект |
| [missing-types](field-context-create-missing-types/scenario.md) | Не указаны типы |
| [missing-both](field-context-create-missing-both/scenario.md) | Не указаны обе оси |
| [unknown-project](field-context-create-unknown-project/scenario.md) | Неизвестное имя проекта |
| [unknown-type](field-context-create-unknown-type/scenario.md) | Неизвестное имя типа |
| [all-types](field-context-create-all-types/scenario.md) | Явно все типы, не отсутствие списка |
| [ambiguous-field](field-context-create-ambiguous-field/scenario.md) | Дубликаты требуют выбора ID |
| [stale](field-context-create-stale/scenario.md) | Реальное изменение hook между preview и согласием |
| [no-questions](field-context-create-no-questions/scenario.md) | «Создай сразу» не отменяет подтверждение |
| [yes-but-task](field-context-create-yes-but-task/scenario.md) | «Да, но добавь ещё Task» — новый запрос |
| [operation-terminal](field-context-create-operation-terminal/scenario.md) | Сразу терминальный CREATED |
| [operation-running](field-context-create-operation-running/scenario.md) | QUEUED/RUNNING → терминал |
| [operation-failure](field-context-create-operation-failure/scenario.md) | Ошибка / PARTIAL, без retry |
| [operation-lost-start](field-context-create-operation-lost-start/scenario.md) | Потерян start без operationId |
| [operation-lost-poll](field-context-create-operation-lost-poll/scenario.md) | Потерян poll при известном operationId |

## Fixtures перед будущим запуском

Каждый scenario.md задаёт самостоятельный профиль, точные реплики, переходы, запреты и последствия. Ветки не используют результаты друг друга.

Базовый профиль BASIC (также основа STALE/OPERATION): новые target и source с именами/ключами штатных placeholders; редактируемый Text Field «Текст RUN», один исходный project-specific контекст source×Bug/Task; target вне всех контекстов; в target доступны Bug/Task/Sub-task. Сохранить baseline поля, контекстов/configs и зависимых конфигураций.

Для регрессии нужны четыре проекта, два одноимённых поля QA RUN разных типов, старый scope source+other×Bug/Task, собственные разделяемые экраны/схемы с outside, общий экран Task/Sub-task, QA на всех нужных CREATE/EDIT/VIEW, optional и not hidden. Точные Bug/Sub-bug/Sub-task подготовить в disposable Jira; не переименовывать глобальные существующие типы.

Для SELECT/SHARED/GLOBAL нужны опции А/Б и default Б, сохранённые ID и содержимое настроек; для проверки исторических данных — собственные задачи с опциями, которые не перезаписываются диалогом. Ограниченный тип требует фактически поддержанного контрактом отказа/предупреждения и установленного плагина, если он нужен.

Будущий setup может использовать существующие tool/apply/rest/adminPage helpers (сейчас не вызывались). Секреты не сохраняются. Нужны ownership manifest и снимки baseline. Очистка только своих объектов вручную через штатное UI с проверкой зависимостей; MCP удаления контекста не предполагается. Runner очистки не делает. Предпочтительно утилизировать целиком отдельный disposable atlas-run, а не чистить общую Jira.

## Блокировки и оценка

1. **Реализация / контракт:** все create-сценарии заблокированы до полноценного сервиса, публикации инструмента, операций и согласованной семантики settings/effectiveChange/warnings. shared-existing использует существующий контракт update, но также требует fixtures/проверок.
2. **Подготовка:** regression-approve имеет setup.mjs/verify.mjs, но запуск требует точных Bug/Task/Sub-bug/Sub-task правильного вида. Остальные сценарии всё ещё требуют fixtures и проверок. Общие checks.mjs сами по себе не доказывают предметную корректность.
3. **STALE:** существующий hooks API подходит для реального межходового изменения. Нужен будущий hook и доказательство, что выбранное изменение входит в fingerprint create. Проверять промежуточный снимок сразу после stale, иначе последующий успех скроет лишнее создание.
4. **Управляемые сбои:** пять operation-* случаев требуют отдельного отсутствующего transport/executor harness. hooks между ходами не может гарантировать QUEUED, терминальный ответ внутри start или потерю ответа одного poll. Не создавать нагрузку на Jira для случайного попадания в состояние и не выдавать эти случаи за обычные live-диалоги.
5. **Три слоя доказательств:** TEXT_TRACE — решения и согласия; STATE — реальные снимки Jira; FAULT — доказательство управляемого исхода. Недоступный слой означает NOT_VERIFIED/BLOCKED, не PASS. Сравнивать смысл и наблюдаемые последствия, а не буквальный ответ. Отдельные чтения проверяющего допустимы и не считаются запрещённым лишним чтением ассистента после CREATED.

## Статическая подготовка

Разрешены только локальное чтение и проверка структуры Markdown, placeholders, локальных ссылок и соответствия загрузчику scenario.md без импорта/вызова runner. Измеренных live-результатов нет.

Выполненная локальная проверка подготовки: 26 файлов сценариев и 26 уникальных ID; у каждого есть восемь обязательных для этого пакета разделов; извлечение первой реплики тем же регулярным выражением, что у runner, совпало с подготовленным текстом. Проверены грамматика placeholders, отсутствие фиксированных production IDs и исходных production ключей, наличие всех сценариев в индексе и существование 31 локальной ссылки. В новых каталогах только scenario.md: исполняемых setup/hooks/verify и результатов runs нет. Runner не импортировался и не выполнялся. Это проверка документов, не live-вердикт.

## Попытка запуска regression-approve

- `node --check` для setup.mjs и verify.mjs: успешно.
- `node evals/live/run.mjs field-context-create-regression-approve --max-turns 15`: код 2, `Required exact issue type missing or wrong kind: Bug`.
- Соединение: localhost:2990/jira, control 1.1.4, 43 инструмента.
- Preconditions проверяются до первой записи. Проекты, поля и контексты этим запуском не создавались; LLM-диалог не начался. PASS не присваивался.
- Setup готовит четыре новых проекта, два одноимённых поля разных типов, собственный общий экран и общую схему target/outside; сохраняет baseline и созданные объекты из operation reports.
- Verify сравнивает исходные контексты, оба поля, назначения и раскладки экранов, конфигурации полей; требует единственного применения create_field_context и точного нового scope без Task. UI и поиск не проверяет.
