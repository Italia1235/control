# Справочник по инструментам

[English](TOOLS.md) | Русский

Контракт каждого из 47 MCP-инструментов: входы, результаты, коды ошибок и границы. Цикл «превью → подтверждение → применение» описан в [README](README.ru.md#как-работают-изменения), правила поведения модели — в [`rules.md`](src/main/resources/llm/rules.md), внутреннее устройство — в [howitworks](howitworks/) (на английском). Здесь они не повторяются.

## Общие правила

Действуют для всех записывающих инструментов; в разделах ниже названы только отличия.

- **Превью.** Доменный инструмент принимает только `dryRun:true` с полным запросом. Превью ничего не пишет и при успехе возвращает `planId`. Если менять нечего (`NO_CHANGE`, `UNCHANGED`, `ALREADY_*`, `EXISTS`) или запрос отклонён, плана нет.
- **Применение.** Только `start_operation` с `toolName` и `arguments:{dryRun:false, planId}`, без полей превью. Прямой вызов доменного инструмента с `dryRun:false` отклоняется.
- **План** привязан к администратору, нормализованному запросу и отпечатку существенного состояния Jira, действует ограниченное время и применяется один раз. Перед записью он пересчитывается под блокировкой; любое расхождение даёт `PREVIEW_STALE` без записи — нужны новое превью и новое подтверждение.
- **Блокировки** Control упорядочивают только операции Control. Интерфейс Jira, REST и другие плагины их не соблюдают.
- **Результат** после записи перечитывается из Jira. `FAILED`, `PARTIAL*`, `UNKNOWN`, `NOT_ATTEMPTED` — не успех: Jira могла измениться частично. Применение неатомарно, автоматических повторов и отката нет; исправление — новое превью.
- **Отмена.** В каталоге MCP её нет. Журнал Control хранит шаги, и для отдельных create-операций служебный откат доступен через REST `/rest/control/1.0/changes/rollback` ([Audit](howitworks/audit/Audit-how-it-works.md), на английском); где это так, сказано в разделе инструмента.
- **Чтение.** Read-only инструменты доступны администратору, ничего не пишут и плана не требуют.

### Протокол операций и восстановления

- Сохраните `operationId` из `start_operation`; после переподключения читайте его через `get_operation`. Если ответ старта потерян, повторите идентичный `start_operation` с тем же `planId`: вернётся существующая операция с `alreadyStarted:true`, новая запись не начнётся. Exactly-once не гарантируется: после отказа узла исход может остаться `UNKNOWN`.
- Если старт уже вернул конечное состояние с результатом, дополнительное чтение не нужно. Иначе `get_operation` ждёт до `waitSeconds` (0–20, по умолчанию 20). `QUEUED`, `RUNNING`, `CANCEL_REQUESTED` — не итог. Не ищите операцию или созданный объект по имени и не повторяйте create ради восстановления.
- `PREVIEW_STALE` с `staleReason:EXPIRED` — план истёк, с `NOT_FOUND` — план недоступен: нужны новое превью и новое подтверждение, даже если запрос тот же. `CONSUMED` — план уже связан с другим инструментом; запись не повторять (тот же инструмент обычно возвращает существующую операцию).
- Отказ из-за отсутствующего или устаревшего плана происходит до создания операции: доменный инструмент не вызывается, строки в журнале применений нет.

## Проекты

### `create_project`

Создаёт проект Business или Software из поддерживаемого шаблона либо читает каталог шаблонов. Режим выбирается аргументом `action`; запрос без `action` отклоняется.

**Каталог: `{action:"list_templates"}`.** Чтение без плана. Строка: `templateKey`, `projectTypeKey`, `name`, `description`, `supported`; сортировка по `projectTypeKey`, затем `templateKey`. `supported=true` только если ключ и тип, сообщённый Jira, совпадают с таблицей; Jira Service Management и сторонние шаблоны — `false`. `OK` с пустым `templates` — Jira шаблонов не сообщает; `FAILED`/`JIRA_ERROR` — каталог не прочитан, а не «шаблонов нет».

| Тип | Шаблон | `templateKey` |
| --- | --- | --- |
| business | Project Management | `com.atlassian.jira-core-project-templates:jira-core-project-management` |
| business | Task Management | `com.atlassian.jira-core-project-templates:jira-core-task-management` |
| business | Process Management | `com.atlassian.jira-core-project-templates:jira-core-process-management` |
| software | Scrum | `com.pyxis.greenhopper.jira:gh-scrum-template` |
| software | Kanban | `com.pyxis.greenhopper.jira:gh-kanban-template` |
| software | Basic | `com.pyxis.greenhopper.jira:basic-software-development-template` |

**Создание: `{action:"create", templateKey, dryRun:true, name, key, lead[, issueTypes, newIssueTypes, defaultIssueType]}`.** `templateKey` — строка каталога с `supported=true`, тип проекта берётся из шаблона. Ключ не генерируется. `lead` — точный username или userKey активного пользователя (`list_project_leads`), не отображаемое имя. Шаблона нет в Jira — `TEMPLATE_UNAVAILABLE`; ключ не из таблицы или Jira сообщает другой тип — `UNSUPPORTED_TEMPLATE`; шаблон пропал до применения — `PREVIEW_STALE`. Хук шаблона выполняет Jira, Control его эффекты не моделирует.

Результаты:
- `CREATED` — проект создан.
- `EXISTS` — проект с тем же ключом, названием, руководителем и типом уже есть; его шаблон и настройки не проверяются и не меняются, `templateKey` пуст.
- `KEY_CONFLICT`, `NAME_CONFLICT`, `LEAD_CONFLICT` — существующий проект не меняется; тот же ключ у проекта другого типа — `KEY_CONFLICT`.

**Типы задач.**
- Без `issueTypes` и `newIssueTypes` проект получает собственные типы шаблона; заранее они неизвестны.
- `issueTypes` (существующие типы, имя или id) и `newIssueTypes` (`{key, name, description?, subtask}`) вместе — **полный итоговый набор**. Определение переиспользует тип с тем же именем (без учёта регистра) и видом, иначе создаётся глобальный тип. Имя типа другого вида, неоднозначное имя, дубли `key` или имён, пересечение `issueTypes` с определением, набор без стандартного типа — `FAILED` до записи.
- `defaultIssueType` — локальный `key` или тип набора, только стандартный. Без него — первый стандартный тип набора (сначала `issueTypes`, потом `newIssueTypes`); превью показывает выбор в `issueTypeSet.defaultIssueType` и `defaultSource` (`REQUESTED`, `FIRST_STANDARD`).
- Существующий проект с явным набором — `KEY_CONFLICT`; для него есть `set_project_issue_types`.

Превью показывает проект, `issueTypeSet.entries` (`key`, `name`, вид, `WOULD_CREATE` или `EXISTS`, id существующих), default, `globalImpact` (новые типы попадают в стандартную схему типов Jira и доступны всем её проектам) и `residualRisks`. Какие типы шаблона будут исключены, становится известно только после создания проекта (`assignment.excludedTypes`). Исключение не удаляет тип глобально: проекту создаётся собственная схема типов, общие схемы не правятся.

Одна операция: проект → недостающие типы → назначение набора. `CREATED` — проект создан **и** набор назначен (`issueTypeSet.assignment.state=COMPLETED` с фактическими `issueTypes`, `excludedTypes`, `defaultIssueType`, `scheme`). `PARTIALLY_CHANGED` — проект есть (`projectId`, `key`), остальное не завершено:
- `issueTypeSet.entries[].status`: `CREATED`, `EXISTS`, `FAILED`, `UNKNOWN`, `NOT_EXECUTED`; id с `UNKNOWN` только наблюдаемый, использовать его нельзя;
- `assignment.state`: `NOT_STARTED` (типы проекта не тронуты), `PARTIALLY_CHANGED` или `UNKNOWN` (подробности в `assignment.execution`).

Набор не назначается, пока не подтверждён каждый тип. Задачи, появившиеся в исключаемом типе шаблона, молча не мигрируются: `MAPPING_REQUIRED`, нужен `set_project_issue_types` с `mappings`. Созданные проект и типы остаются в Jira, их id — в `result.report` и `issueTypeSet`.

**Откат через журнал** возможен только для Business-проекта. Для Software он отклоняется до удаления: доска и фильтр не принадлежат журналу Control.

**Проверка.** Software-шаблоны проверены только unit-тестами; их ключи взяты из документации Atlassian и на Jira 10.3.24 не подтверждены. Task Management и Process Management проверены превью и unit-тестами, без реального создания.

### `list_project_leads`

Ищет активных пользователей по непустому `query`. Возвращает `username`, `displayName`, `userKey`, без email. В `create_project.lead` передаётся точный username или userKey; неоднозначного руководителя выбирает пользователь.

### `list_projects`

Ищет проекты по ключу или части имени: `key`, `name`, не больше `limit` (по умолчанию 20) из `total`. Типы задач включаются, только если найден ровно один проект (`issueTypesIncluded`, `issueTypesTotal`).

## Поля

### `create_custom_fields`

Создаёт кастомные поля (текст, числа, даты, списки, пользователи и т. д.) с контекстами: какие проекты и типы задач. Существование проверяется только по точным именам из запроса. Поле с тем же именем и типом возвращается как `EXISTS` и не меняется. Каталог полей в ответ не выгружается; типы полей перечисляет `list_custom_field_types`.

**Правила контекстов** (общие с `create_field_context` и `update_field_contexts`):
- проект (по id; ключ и имя — тот же проект) входит не более чем в один project-scoped контекст поля, даже при разных типах задач; глобальный контекст (`allProjects:true`) — не более одного;
- один проект с несколькими типами — один контекст с общим списком опций; разные опции требуют разных полей;
- нарушение — `FAILED`/`VALIDATION_ERROR` в превью с проектом, номерами и именами обоих контекстов;
- глобальный контекст рядом с project-контекстами допустим, но в их проектах не действует, в том числе для неперечисленных типов: Jira выбирает контекст проекта раньше глобального. Каждый такой project-контекст получает предупреждение.

Ограничение — правило плагина: поведение Jira при нескольких project-контекстах с одним проектом не проверялось.

**Проверка после записи.** Поле перечитывается, контексты, проекты, типы задач и опции сверяются с планом. `CREATED` — только при полном совпадении. Иначе `PARTIAL` с `errorCode:CONTEXT_MISMATCH`, реальным `customFieldId`, `contexts` в том виде, как их вернула Jira (пусто, если чтение не удалось), и списком расхождений в `message`. Очистка не выполняется.

Настройки сторонних типов полей задаются в определении поля и одинаковы для всех его новых контекстов. Для существующего поля (`EXISTS`) они игнорируются и перечислены в сообщении. Все интеграции опциональны: без плагина обычные поля работают.

**Assets object** (`com.riadalabs.jira.plugins.insight:rlabs-customfield-default-object`):

```json
"assets": {
  "objectSchemaId": 123,
  "filterScopeAql": "objectType = \"Server\""
}
```

`objectSchemaId` (обязателен) берётся из `list_assets_schemas`; `filterScopeAql` необязателен, пустая строка — без фильтра. Схема и AQL проверяются в превью и повторно при применении. `options` для Assets не используются. Настройки пишет сервис Assets. При компенсации журнал не удаляет созданное поле с настройками Assets; очистка — вручную средствами Jira. Проверено на Assets 8.11.2 / Jira 9.12.2; на Jira 10.3.24 не проверялось.

**Apwide File Field** (`com.apwide.document.file-field:apwide-doc-field`):

```json
"fileField": {
  "allowedFileExtensions": ["pdf", ".docx"],
  "maxFiles": 3,
  "maxFileSizeMb": 5,
  "readonly": false,
  "autoClean": true
}
```

Все параметры необязательны; без `fileField` действуют умолчания плагина (любые расширения, 10 файлов, лимит загрузки Jira). К расширению без точки точка добавляется (сам плагин такие молча отбрасывает), повторы без учёта регистра отклоняются. Размер — в десятичных МБ, как в интерфейсе плагина. Проверено на File Field 4.0.5 / Jira 10.3.24.

**Table Grid Next Generation** (`tge.cloud:idalko-table-grid-custom-field-key`):

```json
"grid": {
  "columns": [
    {"id": "name", "title": "Имя", "type": "string", "required": true},
    {"id": "color", "title": "Цвет", "type": "list", "options": ["Red", "Green"], "defaultValue": "Green"},
    {"id": "qty", "title": "Кол-во", "type": "number", "decimals": 2}
  ],
  "searchableColumns": ["name"]
}
```

Типы колонок: `string`, `textarea`, `number`, `integer`, `date`, `datetime`, `checkbox`, `list`, `multiselectlist`, `userlist`, `sequence`. Колонки заменяют колонки плагина по умолчанию (Order, Summary, Assignee, Status) во всех новых контекстах; без `grid` плагин ставит их только в первый контекст. Превью проверяет уникальность id, `options` только у списков, `defaultValue` из `options`, существование `searchableColumns`, затем — валидацией самого плагина. Формулы, вложения, списки из data source, grid behaviors и строки по умолчанию не поддерживаются. Проверено на TGNG 6.5.0 / Jira 10.3.24.

Откат настроек File Field и TGNG не реализован; само поле журналируется как обычно.

### `list_assets_schemas`

Без аргументов. Возвращает `id`, `name`, `description` схем объектов установленного Assets. Схему не создаёт; если Assets нет или он выключен — ошибка.

### `list_custom_field_types`

Без аргументов. Возвращает установленные типы кастомных полей и совместимые searchers, включая `supportsOptions`.

### `get_field_contexts`

Читает контексты одного поля, без обхода других полей и задач. Вход: `field` (точное имя, `customfield_N` или `N`), необязательные `project` (ключ или точное имя; ключ приоритетнее), `contextId` и `includeOptions` (`true` требует `contextId`, иначе `VALIDATION_ERROR`).

```json
{"field":"customfield_10103","project":"SALES"}
```

- **Ответ:** `field {id, name, typeKey, typeName, typeClass SYSTEM|KNOWN_PLUGIN|THIRD_PARTY, managed, locked}` и `contexts[]` по возрастанию id: область по обеим осям (`allProjects`/`projects`, `allIssueTypes`/`issueTypes`), `configs[{configId, issueTypeIds}]` (`null` — все типы), `options {count, disabledCount}` (`null` — опций нет), `defaultValue PRESENT|ABSENT|UNKNOWN`, `pluginSettings NOT_APPLICABLE|PRESENT|ABSENT|UNKNOWN`; у поля Assets object — `assets {objectSchemaId, filterScopeAql}`.
- **Проекты:** без `contextId` — до 50 на контекст (`projectsComplete:false` при обрезке), с `contextId` — полный список. Глобальность показывается флагами.
- **`project`** отбирает глобальные контексты и контексты, содержащие проект; проверка идёт по полному списку, а не по первым 50. Это фильтр, а не вычисление эффективной конфигурации: счётчики и проекты описывают всю область контекста, типы задач не урезаются. Нет совпадений — `READ` с `contexts:[]`. `contextId`, не принадлежащий полю, — `UNKNOWN_CONTEXT`.
- **`includeOptions:true`** добавляет `optionList {hasOptions, items[{configId, id, value, parentId, disabled, default, position}]}`: родитель, затем его дети; `position` — с 0 внутри своего уровня; `default` отсутствует, если значение по умолчанию не прочитано. Только эти `id` принимаются как `optionId` в `update_field_contexts`.
- `managed` — поле управляется приложением; `locked` — текущий администратор не вправе его менять (как в форме Jira). Managed-поле с уровнем доступа ADMIN: `managed=true`, `locked=false`.
- `defaultValue` стандартных типов определяется по хранимой строке: default со ссылкой на удалённого пользователя или версию остаётся `PRESENT`.
- `UNKNOWN` — «не удалось прочитать», а не «отсутствует». Настройки Grid всегда `UNKNOWN`; Assets и File Field читаются.
- **Ошибки:** `UNKNOWN_FIELD`, `AMBIGUOUS_FIELD` (+`candidates` с id и типом), `UNKNOWN_CONTEXT`, `UNKNOWN_PROJECT`, `VALIDATION_ERROR` (архивный проект, неоднозначное имя проекта, `includeOptions` без `contextId`). Ошибки проекта содержат `suggestions`. Аргументы неверного типа отклоняются до чтения Jira.

### `update_field_contexts`

Меняет у существующих контекстов одного поля область (проекты × типы задач), настройки Assets object и/или опции. Вход: точный `fieldId:"customfield_N"` и до 10 `contexts` из `get_field_contexts`. Новые контексты не создаёт (это `create_field_context`).

**Область.** Для каждого `contextId` нужны обе оси целиком: `projects:[ключи или имена]` либо `allProjects:true` и `issueTypes:[id или имена]` либо `allIssueTypes:true`. Одна ось, пустые списки, обе формы сразу, `false` вместо глобальности — `VALIDATION_ERROR`.
- Проверяется весь итоговый набор контекстов, включая неизменяемые, по правилам из `create_custom_fields`. `FIELD_LOCKED` повторяет права Jira; `MULTIPLE_CONFIGS` — у контекста несколько разных FieldConfig.
- Превью: `WOULD_UPDATE` с планом или `UNCHANGED` без плана; `before`/`after`, `preservedConfigIds`, изменения проектов, типов и сочетаний, предупреждения, `unchangedContexts`. В сочетаниях проекты — ключами, типы — id, глобальность — `*`; не более 50 строк (`combinationsComplete`). При `countUnit:SYMBOLIC_COMBINATIONS` счётчик считает символические строки, а не проекты и задачи.
- Сохраняются FieldConfig, опции (кроме названных в `options`), defaults и читаемые настройки Assets/File Field (кроме переданных в `assets`). Для Grid и сторонних типов — `PLUGIN_SETTINGS_UNVERIFIED`: сохранность их настроек проверить нельзя.
- Сужение не удаляет значения задач, но может скрыть их из интерфейса и поиска. Jira получает уведомление о необходимости переиндексации, сама переиндексация не запускается; `notificationDisplayed:false` — не удалось доставить уведомление, область при этом изменена.
- Изменение области, опций, default или читаемых настроек плагинов после превью — `PREVIEW_STALE`.

**Assets.** У поля Assets object можно передать `assets {objectSchemaId, filterScopeAql}` без осей (меняются только настройки) или вместе с обеими осями (один план). `assets` у поля другого типа, несуществующая схема или невалидный AQL — `VALIDATION_ERROR`. Превью и результат показывают `contexts[].assets {before, after}`. Сохранённые значения должны совпасть с одобренными, прочие свойства конфигурации Assets — не измениться; иначе `PARTIAL` (или `FAILED`, если ничего не изменилось).

**Опции** (select, radio buttons, checkboxes, multi-select, cascading select): `contexts[].options` — отдельно или вместе с осями и `assets` в одном плане.
- `add [{value, parentOptionId?}]` — новая включённая опция в конец своего уровня; ребёнок — только под существующим родителем cascading select.
- `rename [{optionId, value}]`, `disable [optionId]`, `enable [optionId]`.
- `order [{parentOptionId?, optionIds}]` — все существующие опции уровня, включая отключённые, в новом порядке; новые встают после них в порядке `add`.
- `optionId` — только из `get_field_contexts` (`contextId` + `includeOptions:true`). Значения обрезаются по краям, до 255 символов.
- Удаления нет: опцию убирают из выбора отключением, значения в задачах сохраняются. Отключить default можно (с предупреждением), сам default не меняется. Родителя cascading select можно отключить только вместе со всеми включёнными детьми; под отключённым родителем ребёнка нельзя добавить или включить.
- Порядок записи: add → rename → enable/disable → order. Значения задач не читаются и не пишутся, переиндексация не нужна.
- Превью: у каждого контекста `options {rows, unchanged, warnings}` — «было → стало» (`changes`: `RENAMED`, `DISABLED`, `ENABLED`, `MOVED`, `ADDED`; значение, `disabled`, `position`, пометка `default`). Для переименуемой и отключаемой опции — `usage {status COUNT|UNKNOWN, issues|reason}`: число задач по id опции, только в превью, общий бюджет 10 секунд; сбой — `UNKNOWN` с причиной. Число не входит в отпечаток плана. Операции без эффекта помечаются `unchanged`; если таких все — `OPTIONS_NO_CHANGE`. Предупреждения: `RENAME_CHANGES_ALL_ISSUES`, `DISABLE_KEEPS_ISSUE_VALUES`, `DEFAULT_OPTION_DISABLED`.
- Default не прочитан — `JIRA_ERROR` без плана (при применении — `PREVIEW_STALE`).
- Отказы без плана: `OPTION_NOT_IN_CONTEXT`, `OPTION_DUPLICATE` (в итоговом уровне, без учёта регистра, включая отключённые; обмен значениями и смена регистра допустимы), `OPTION_VALUE_INVALID`, `OPTION_ORDER_INVALID`, `OPTION_CONFLICT` (одна опция в `disable` и `enable` или повтор id), `OPTIONS_NOT_SUPPORTED` (поле не option-типа, Assets, плагин), `OPTION_PARENT_HAS_ENABLED_CHILDREN`, `OPTION_PARENT_DISABLED`, `OPTIONS_NO_CHANGE`, `OPTIONS_LIMIT`.
- `OPTIONS_LIMIT`: 100 опций верхнего уровня, 100 детей на родителя, 500 на контекст, 2000 названных опций на запрос (и в одном `order`). Первые три проверяются только при `add` в соответствующий уровень или контекст: контекст, уже превысивший лимит, допускает `rename`/`enable`/`disable`/`order`.
- После записи опции сверяются с одобренным итогом (существующие — по id: значение, родитель, disabled, порядок; новые — по значению, родителю и месту; остальные не изменены), default и настройки плагинов не должны измениться. Ничего не записано — `FAILED`, записано частично — `PARTIAL`. Опции не удаляются ни при сбое, ни при применении.

Отмены нет. Прежнее состояние возвращает новый подтверждённый запрос по `before` из превью.

### `create_field_context`

Создаёт ровно один **новый** контекст существующего поля. Существующие контексты меняет `update_field_contexts`; `create_custom_fields` для существующего поля возвращает `EXISTS`.

```json
{"dryRun":true,"fieldId":"customfield_10500","name":"QA: QABUG bugs","description":"Bug family",
 "projects":["QABUG"],"issueTypes":["Bug","Sub-bug","Sub-task"]}
```

- По каждой оси ровно одна форма: `projects` либо `allProjects:true`, `issueTypes` либо `allIssueTypes:true`. Пустой список, обе формы, `false` вместо глобальности, отсутствие оси — `VALIDATION_ERROR`; неявного глобального контекста нет. Лимиты: 100 проектов, 50 типов, имя и описание до 255 символов.
- Итоговый набор проверяется по правилам из `create_custom_fields`: `PROJECT_IN_OTHER_CONTEXT`, `GLOBAL_CONTEXT_EXISTS`; занятое имя — `CONTEXT_NAME_EXISTS`. Конфликтующий контекст и проект — в `conflict`; конфликт не исправляется расширением чужого контекста. Прочие ошибки: `UNKNOWN_FIELD`, `FIELD_LOCKED`, `UNKNOWN_PROJECT`, `UNKNOWN_ISSUE_TYPE` (с подсказками), `UNSUPPORTED_FIELD_TYPE`, `JIRA_ERROR`.
- **Превью** (`WOULD_CREATE`): поле и тип, имя, описание и область нового контекста, `unchangedContexts`, `effectiveChange`, `settings`, `warnings`.
  - Project-контекст перекрывает глобальный для **всех** типов своего проекта. `configSwitches` и `OVERRIDES_GLOBAL_CONTEXT` показывают сочетания, переходящие с глобального config на новый; `lostApplicability` и `GLOBAL_APPLICABILITY_REMOVED` — типы вне нового контекста, теряющие поле; `existingBehaviorChanged` учитывает оба эффекта. Значения задач не удаляются, но применимость, видимость и поиск могут измениться.
  - Для нового глобального контекста `keptByExistingContexts` показывает сочетания, оставшиеся за project-контекстами; `newlyApplicableExact:false` означает верхнюю оценку. Счётчики с `*` символические.
- **Настройки.** Новый контекст получает один новый FieldConfig со штатной начальной конфигурацией Jira; опции, defaults, фильтры пользователей и прочие настройки существующих контекстов **не копируются**. Поддерживаемые типы: text, textarea, date picker, date time picker, number, URL, labels, user/multi user picker, group/multi group picker, project picker, version/multi version picker, а также Assets object с обязательным для него `assets {objectSchemaId, filterScopeAql}` (проверка как в `update_field_contexts`, результат в `settings.pluginSettings.assets`). Списки, File, Grid, прочие сторонние и неизвестные типы, Assets без `assets` или с выключенным плагином — `UNSUPPORTED_FIELD_TYPE`.
- **Результат.** Существующие контексты, экраны, field configurations, workflow и значения задач не меняются. `CREATED` — после перечитывания: имя, описание, область, один новый FieldConfig без опций, сохранённых defaults и лишних настроек (для Assets — одобренные schema/AQL), существующие контексты не изменились. Иначе `PARTIAL`; ошибка внутри записи Jira тоже даёт `PARTIAL`, а не «ничего не записано». `FAILED` — отказ до записи при перечитанном неизменном состоянии. Известный `contextId` возвращается в результате и `report` даже без подтверждения; `actual:null` — область не подтверждена.
- Отмены нет, в том числе служебной: безопасного удаления контекста Jira не обеспечивает. Удаляйте вручную, проверив значения задач и возврат проектов под глобальный контекст.
- Jira получает уведомление о необходимости переиндексации (`reindexNotificationDisplayed`), сама переиндексация не запускается.

### `add_field_to_screens`

Добавляет **каждое** из нескольких полей на **каждый** из нескольких экранов: кастомные и системные (`assignee`, `reporter`, `priority`, `duedate`, `labels`, `components` и др.). Поля с разными наборами экранов — отдельными вызовами. Для одного экрана используйте `edit_screen_layout`: добавление, перемещение и удаление на нём — один план. Экраны для кастомного поля подбирает `plan_field_screens`; для системных `screenId` берётся из `get_project_screens` или от пользователя.

Элемент `screens[]`: `screenId`, необязательные `tab`, `place` (`FIRST`, `LAST`, `BEFORE`, `AFTER`) и `anchor`.
- Без `place` поле встаёт последним на выбранной (или первой) вкладке, как при `LAST`.
- `BEFORE`/`AFTER` требуют `anchor` — точный `fieldId` на целевой вкладке из `get_screen_contents` (не имя; число превращается в `customfield_N`). `anchor` с `FIRST`/`LAST`, его отсутствие у `BEFORE`/`AFTER`, а также `UP`/`DOWN` — `VALIDATION_ERROR`.
- Несколько полей с одним `AFTER`/`FIRST` встают подряд в порядке `fieldIds`.
- Якоря нет на вкладке — `FAILED`/`UNKNOWN_ANCHOR` только для этого экрана. Поле уже на экране (на любой вкладке) — `ALREADY_PRESENT`, не перемещается.
- Позиции вкладки с пропусками сначала перенумеровываются 0..n-1 без смены порядка. После записи экран перечитывается: новое поле на месте, остальные в прежнем порядке с прежним `shownWhenEmpty`. Новая строка получает `shownWhenEmpty:false`.
- Изменение раскладки выбранных экранов после превью — `PREVIEW_STALE`.

**Сбои.** Уже обработанные экраны не откатываются. Неподтверждённая запись (в том числе расхождение при перечитывании) — `UNKNOWN`/`JIRA_ERROR` для экрана: поле могло добавиться. Сбой до записи — `FAILED`/`JIRA_ERROR` только для этого экрана; оставшиеся пары — `NOT_ATTEMPTED`. В Audit общий статус: `ADDED` рядом с `ALREADY_PRESENT` — `MIXED`; успех рядом с `UNKNOWN`/`FAILED`/`NOT_ATTEMPTED` — `PARTIAL`; без успехов — `UNKNOWN` или `FAILED`.

### `set_field_required`

Задаёт обязательность полей по строкам `rows[]` в схеме конфигураций полей одного проекта: один план на весь пакет.

```json
{"dryRun":true,"project":"PAY","rows":[
 {"fields":["customfield_10100"],"required":true,"issueTypes":["Оплата","Снабжение"]},
 {"fields":["customfield_10101","duedate"],"required":false,"allIssueTypes":true}]}
```

- `rows` 1–20; строка: `fields` 1–10, явный `required`, `issueTypes` либо `allIssueTypes:true`. Поле одного типа задач — ровно в одной строке, иначе `ROW_CONFLICT` по паре тип/поле.
- Кастомное поле — точное имя, `customfield_N` или число; системное — id Jira (`priority`, `duedate`, `components`, `fixVersions`, `labels`, `assignee`…). Имя и id одного поля — дубликат.
- Любое неизвестное, неоднозначное или locked-поле отклоняет весь пакет. `fieldErrors[]` перечисляет каждую проблему (`selector`, `errorCode`, `message`, кандидаты); верхний `errorCode` — первая ошибка. Коды: `UNKNOWN_FIELD`, `AMBIGUOUS_FIELD`, `LOCKED_FIELD` (обязательные для Jira поля, например `summary`, `issuetype`, нельзя сделать необязательными), `FIELD_REQUIRED_UNSUPPORTED`, `ROW_CONFLICT`.

**Превью** показывает эффективную схему, конфигурацию каждого типа, статус каждой пары в `targets[].fields[]`, типы, переходящие на копию и остающиеся на исходной, и другие затронутые проекты. Статусы пакета: `WOULD_CHANGE_REQUIREMENTS` / `REQUIREMENTS_CHANGED` / `ALREADY_AS_REQUESTED` (без плана), `PARTIALLY_CHANGED`, `FAILED`. Статусы пары: `WOULD_MAKE_REQUIRED` / `MADE_REQUIRED` / `ALREADY_REQUIRED`, `WOULD_MAKE_OPTIONAL` / `MADE_OPTIONAL` / `ALREADY_OPTIONAL`. Работает с явной схемой проекта и со стандартной схемой Jira. Снятие обязательности не меняет задачи; валидаторы workflow и настройки приложений не трогаются.

**Копии конфигураций.** Обязательность хранится в Field Configuration, а не в типе задачи. Итоговые конфигурации считаются по всему пакету:
- выбранные типы с одинаковым итогом делят одну копию исходной конфигурации (`newFieldConfigurationName`, например `PAY: Shared fields (Task)`); разные итоги — по копии на итог;
- если у исходной конфигурации нет невыбранных типов, она правится на месте;
- типы без изменений и невыбранные остаются на исходной; исходная без назначений не удаляется;
- копии требуют схемы, принадлежащей только этому проекту, иначе `SHARED_FIELD_CONFIGURATION_SCHEME` — сначала `clone_project_field_configurations`.

**Attachment.** Системное поле `attachment` нельзя сделать обязательным через Field Configuration: `required:true` даёт `FIELD_REQUIRED_UNSUPPORTED` и отклоняет весь пакет. Это ограничение Jira; validator в workflow — отдельный механизм. Одноимённое кастомное поле и `required:false` не затрагиваются.

**Проверка CREATE (`required:true`).** Для каждого типа берётся эффективный экран CREATE (строка `CREATE` Screen Scheme, иначе `DEFAULT`) и проверяется: поле в контексте проекта и типа и в Field Configuration (`FIELD_NOT_APPLICABLE`), не скрыто (`FIELD_HIDDEN`), есть на этом экране (`FIELD_NOT_ON_CREATE_SCREEN`; EDIT/VIEW не засчитываются, позиция не важна). Все проблемные пары — сразу в `fieldErrors[]`. Уже обязательное, но недоступное при создании поле тоже отклоняется. Экраны или схема не прочитаны — `JIRA_ERROR`. Порядок работ: контексты → раскладка экрана → назначение экранов → обязательность.

В отпечаток входят поля, их контексты, конфигурации, строки схемы, экраны CREATE и присутствие на них полей. Лимит 10 полей на строку ограничивает результат 500 парами поле/тип при 50 типах.

**Сбой.** `partialEffect` перечисляет каждый `REQUIRED_FLAG` (`APPLIED`, `UNKNOWN`, `NOT_STARTED` по `fieldId`), копии и маппинги, отдельно — компенсированное. После сбоя созданная копия удаляется, только если на неё нет ссылок, а строка схемы возвращается, только пока указывает на копию этой операции; иначе они в `leftBehind`. Чужие изменения не затираются (`leftBehind`, `UNKNOWN`). Сбой первой же записи тоже даёт `partialEffect`: флаг — `UNKNOWN`, остальное — `notStarted`.

### `get_field_configurations`

Два взаимоисключающих режима.

**Проект `{project[, fields]}`.** Схема конфигураций полей проекта (или схема Jira по умолчанию), строка `DEFAULT` (`defaultMapping`), эффективная конфигурация каждого типа проекта (`explicit:false` — через строку `DEFAULT`), строки для типов вне проекта (`otherEntities`), каждая используемая конфигурация с другими проектами и числом схем. `fields` (до 50) добавляет флаги `required`, `hidden`, `present` в каждой конфигурации и `locked` для обязательных полей Jira. Схема назначается проекту, а не типу: «для Task» — это строка `Task` схемы проекта.

**Поиск `{kind, id | name[, offset, limit]}`.** Field Configurations (`kind=CONFIGURATION`) или схемы (`kind=SCHEME`), включая неиспользуемые.
- `id` — число или `DEFAULT`; не найдено — `FAILED` `UNKNOWN_FIELD_CONFIGURATION` / `UNKNOWN_SCHEME`.
- `name` — подстрока без учёта регистра; `limit` 1–50 (20), `offset` из `nextOffset` (нет — последняя страница), `total`. Одинаковые имена — отдельные кандидаты с id.
- `usage` у каждого элемента. Конфигурация: `links[]` (схема и строка «тип → конфигурация»; `defaultRow` — строка `DEFAULT`; `fallback` — у схемы нет строки `DEFAULT`, и её типы без своей строки получают Default Field Configuration), `total`, `schemesTotal`. Схема: `projects[]`, `total`.
- `usage.status=READ` ещё не значит, что все связи в ответе: при `truncated=true` дочитывайте `kind`+`id`+`usageOffset=nextUsageOffset` (`usageLimit` до 100; в поиске по имени — первые 20). `UNAVAILABLE` — «неизвестно», не «не используется». Объект не используется, только если `READ`, не усечено и `total=0`.

```json
{"project":"PAY","fields":["duedate"]}
{"kind":"CONFIGURATION","id":"10100"}
{"kind":"SCHEME","name":"PAY","limit":20,"offset":20}
```

### `create_field_configuration`

Создаёт Field Configuration — точную копию существующей (`copyFrom`: id или `DEFAULT`, по умолчанию `DEFAULT`): флаги required/hidden, описания, renderers. Новая конфигурация не используется ни одной схемой, поэтому ничего не затрагивает, пока схема не сопоставит ей тип. Имя уникально без учёта регистра; занятое — `NAME_TAKEN` со свободным вариантом в `suggestions`.

Id созданной конфигурации — `result.fieldConfiguration.id` (и `result.report.created`, `kind: FIELD_CONFIGURATION`); его принимает `create_field_configuration_scheme`. Если исход неизвестен, читайте операцию по `operationId`: повторное превью с тем же именем вернёт `NAME_TAKEN`, только если объект уже создан.

### `create_field_configuration_scheme`

Создаёт Field Configuration Scheme из строк `issueType → fieldConfiguration`. `issueType` — имя или id типа либо `DEFAULT` (обязательная строка для остальных типов); `fieldConfiguration` — id или `DEFAULT`. Схема создаётся без проекта (`assigned:false`); назначает её `assign_field_configuration_scheme`. Имя уникально, включая встроенную «Default Field Configuration Scheme».

Id схемы — `result.scheme.id` (и `result.report.created`, `kind: FIELD_CONFIGURATION_SCHEME`). Использованные конфигурации — в `result.report.reused`; на что ссылается каждая строка — `result.mappings[].fieldConfiguration.id`.

### `assign_field_configuration_scheme`

Назначает проекту существующую схему конфигураций полей (`scheme`: id) или возвращает схему Jira по умолчанию (`DEFAULT`). Данные задач не меняются, но дальше задачи создаются и редактируются по новым правилам. Превью перечисляет типы со сменой конфигурации и поля с изменённым required/hidden (кастомные — только в контексте; до 50 на тип, `fieldsTotal`/`truncated`). Предупреждения: `NEWLY_REQUIRED`, `NEWLY_HIDDEN`, `SHARED_SCHEME` (схему используют другие проекты), `SCHEME_ORPHANED` (старая схема остаётся без проектов и не удаляется). Уже назначенная схема — `ALREADY_ASSIGNED`.

### `set_field_configuration_mappings`

Патч строк существующей схемы: какую конфигурацию получает тип задачи. Перечисленные строки ставятся, остальные не трогаются. `fieldConfiguration`: id, `DEFAULT` или `INHERIT` (удалить строку типа — он перейдёт на строку `DEFAULT`; её саму удалить нельзя). Схему Jira по умолчанию менять нельзя — `DEFAULT_SCHEME`. Изменение действует на все проекты схемы: превью показывает строки до/после, `projectsTotal` и затронутые проекты с типами и полями (до 50). Предупреждения: `SHARED_SCHEME`, `NEWLY_REQUIRED`, `NEWLY_HIDDEN`. Без изменений — `NO_CHANGE`.

### `clone_project_field_configurations`

Даёт проекту независимую копию текущей Field Configuration Scheme и каждой используемой Field Configuration: маппинги типов, порядок полей, видимость, обязательность, описания, renderers. Для стандартной схемы создаются явные копии. Имена содержат ключ проекта, при конфликте — суффикс `vN`. Сначала создаются копии, затем проект переключается на новую схему; задачи не меняются. Если схема и все конфигурации уже используются только этим проектом — `ALREADY_SEPARATE`.

## Экраны

### `get_project_screens`

Читает полную эффективную иерархию экранов одного проекта: Issue Type Screen Scheme → Screen Scheme каждого типа задач → экраны CREATE/EDIT/VIEW, а также другие проекты, использующие эти объекты. Вкладки и поля экрана читает `get_screen_contents`.

### `get_screen_contents`

Читает один экран: `{"screenId":123}`. Id берётся из `get_project_screens`: имена экранов не уникальны. Использование экрана проектами здесь не читается.

- Ответ: `READ`, `screen {id, name, description}`, полные `totalTabs`/`totalFields`, `tabs [{id, name, position, fieldCount, fields}]` по фактическому `position`.
- Поле: `layoutItemId`, `fieldId`, `name`, `kind` (`SYSTEM`, `CUSTOM`, `UNKNOWN`), `position`, `shownWhenEmpty`. Неразрешимое поле остаётся в ответе с `UNKNOWN` и `name:null`.
- Страница — до 200 полей и пустых вкладок. Разрезанная вкладка повторяет метаданные и полный `fieldCount`. При `nextCursor` продолжайте с тем же `screenId` до `complete:true`. Курсор привязан к хэшу всего экрана: при «Screen contents changed» начните заново без курсора.
- Неизвестный id — `FAILED`/`SCREEN_NOT_FOUND`.
- Поле на экране ещё не значит, что оно доступно типу задачи: влияют контексты, Field Configuration и права.

### `plan_field_screens`

Подбирает экраны CREATE/EDIT/VIEW по фактическим контекстам кастомного поля (проекты и типы задач). Показывает вкладки, наличие поля, совместное использование и оценку: `RECOMMENDED`, `CAUTION`, `NOT_RECOMMENDED`, `ALREADY_PRESENT`. Ничего не меняет. `total` и `summary` первой страницы описывают всех кандидатов; `nextCursor` дочитывают только ради экранов текущего превью `add_field_to_screens`.

### `create_screen`

Создаёт отдельный экран с полями, без привязки к проекту и схеме. Одна вкладка `Field Tab`. Существующие системные и кастомные поля переиспользуются; отсутствующие кастомные поля создаются в глобальном контексте после явного выбора типа (`MISSING_TYPE` — выберите тип из `list_custom_field_types`). Если созданное поле не совпало с планом при перечитывании (`CONTEXT_MISMATCH`), экран не создаётся, а ошибка называет id, имя и расхождение поля.

### `edit_screen_layout`

Одна правка раскладки **одного** экрана: добавление существующих полей, перемещение внутри вкладки и между вкладками, удаление строк. Поля, вкладки и данные задач не создаются.

```json
{"dryRun":true,"screenId":123,
 "add":[{"fieldId":"customfield_104","place":"LAST"}],
 "moves":[{"fieldId":"customfield_103","place":"AFTER","anchor":"summary"}],
 "remove":["customfield_102"]}
```

- Хотя бы один из `add`, `moves`, `remove`; всего 1–50 действий. `fieldId` — системный id или `customfield_N`/`N`, не имя. Перед превью прочитайте все страницы `get_screen_contents`.
- Этапы `remove` → `add` → `moves`, каждый видит результат предыдущего. Дубликаты, одно поле в нескольких списках, якорь на удаляемое поле — `VALIDATION_ERROR` с `validationErrors[]`. Позицию нового поля задают в `add`, не в `moves`.
- `add`: `tab` — id или имя вкладки (по умолчанию первая); `place` — `FIRST`, `LAST` (по умолчанию), `BEFORE`, `AFTER` (последние два с `anchor`). Уже размещённое поле — `ALREADY_PRESENT`, не перемещается. Контекст поля не расширяется (`FIELD_CONTEXT_UNCHANGED`); для подбора по контекстам — `plan_field_screens`.
- `moves`: без `tab` поле остаётся на своей вкладке; `place` также `UP`/`DOWN` со `steps` 1–200 (по умолчанию 1), без `tab`/`anchor`. Выход за край — `CLAMPED`.
- `remove`: отсутствующее поле — `NOT_PRESENT`; показываются прежние вкладка, позиция, соседи и предупреждения `HIDES_VALUES`, `EMPTY_TAB`, `REQUIRED_ON_CREATE` — как у `remove_field_from_screens`. `summary` на экране CREATE защищён.
- Превью: `WOULD_EDIT`; `add[]`, `moves[]`, `remove[]` со статусами; `tabs[]` с `before`/`after` (окна ±5 для длинных вкладок, `truncated:true`), влияние и предупреждения общего/default экрана. Итог без изменений плана не даёт.
- Отпечаток — весь экран с позициями и контексты добавляемых полей. Запись — один проход итоговой раскладки, затем перечитывание всего экрана.
- Итог: `EDITED`, `PARTIAL`, `RESTORED`, `FAILED`, `UNKNOWN`, фактическая раскладка в `actual[]`. При неудачном переносе допускается одна попытка вернуть поле; `RESTORED` — весь экран совпал с `before`. `UNKNOWN` — перечитать не удалось.
- Не стройте два плана на один экран до применения первого: они делают друг друга устаревшими.

### `remove_field_from_screens`

Убирает каждое названное поле с каждого выбранного экрана: до 50 полей, 100 экранов и 500 пар, `fieldIds` без повторов. Для одного экрана — `edit_screen_layout`. Удаляется только строка раскладки: поле, значения задач и поиск остаются. Экраны переходов workflow не обходятся. «Убрать из проекта» — экраны CREATE/EDIT/VIEW этого проекта, а не все экраны Jira.

```json
{"dryRun":true,"fieldIds":["customfield_10100"],"screens":[{"screenId":10200}]}
```

- Превью: `WOULD_REMOVE` (с планом), `NOT_PRESENT` (поля нигде нет) или `FAILED`. `results[]` по паре экран × поле: `status` (`WOULD_REMOVE`, `NOT_PRESENT`, `FAILED`, `NOT_ATTEMPTED`), `tab`/`tabId`, прежний `position`, соседи `before`/`after`, `warnings`. `screens[]`: операции CREATE/EDIT/VIEW, проекты, `defaultScreen`.
- Предупреждения: `SHARED_SCREEN` (число проектов, включая выбранный), `DEFAULT_SCREEN`, `HIDES_VALUES` (поле пропадёт из просмотра, значения остаются и ищутся), `EMPTY_TAB`, `REQUIRED_ON_CREATE`.
- `REQUIRED_ON_CREATE` — предупреждение, а не отказ: поле обязательно для пар проект/тип, чьи экраны CREATE затронуты. `requiredPairs` — число пар, `requiredProjects[]` — до 50 проектов с типами (`requiredProjectsTruncated`). Пока поле обязательно, создание задач в этих парах перестанет работать; снять обязательность можно через `set_field_required` с `required:false` до удаления.
- `PROTECTED_FIELD` — `summary` на экране CREATE: строка `FAILED`, остальные планируются. Прочие коды: `UNKNOWN_FIELD` (весь вызов), `UNKNOWN_SCREEN` (строки экрана), `VALIDATION_ERROR`, `JIRA_ERROR`.
- Экраны независимы; индекс строки сверяется по `fieldId` перед каждым удалением. Итог: `REMOVED`, `PARTIAL`, `FAILED`, `UNKNOWN`; по паре — `REMOVED`, `FAILED`, `NOT_ATTEMPTED`, `UNKNOWN`. `actual[]` — перечитанный порядок вкладок (`layoutItemId`, `fieldId`, `position`, `shownWhenEmpty`).
- Вернуть поле: `edit_screen_layout.add` на прежнюю вкладку с `place:AFTER` и `anchor` = сосед `before` из ответа (или `FIRST`). Вернувшаяся строка получает `shownWhenEmpty:false`.

### `clone_project_screens`

Отделяет один проект от общей настройки экранов. Верхняя Issue Type Screen Scheme копируется, только если она общая или стандартная; собственная нестандартная сохраняет id и имя, а её строки переключаются на копии.

- `scope` дочерних Screen Schemes: `SCHEME_ONLY`, `ALL`, `SELECTED`; при `SELECTED` невыбранные типы сохраняют исходные схемы, включая default.
- `copyScreens` — копировать ли сами экраны.
- Превью и результат показывают `COPY` или `KEEP` по каждому уровню. `otherProjects` исключает целевой проект и относится к конкретному объекту.
- Все выбранные уровни уже приватны и верхняя схема нестандартная — `ALREADY_SEPARATE`.
- Сбой после начала записи — `PARTIALLY_CLONED`/`JIRA_ERROR`: созданные копии (`issueTypeScreenScheme`, `screenSchemes`, `screens` с `newId`), этап сбоя и вызов с неподтверждённым исходом (объект мог быть создан). Ничего не создано — `FAILED`/`JIRA_ERROR`. Переключение проекта подтверждается отдельно; если оно не подтверждено, прочитайте схему проекта.

### `set_project_operation_screens`

Назначает существующие экраны операциям `CREATE`, `EDIT`, `VIEW` одного проекта для любого числа типов задач одним планом. Экраны не создаются и не копируются. Экраны переходов workflow назначает `configure_workflow`.

```json
{"dryRun":true,"project":"<ключ>","mappings":[
 {"issueTypes":["Task"],"screens":{"CREATE":"<screenId>","EDIT":"<screenId>"}},
 {"issueTypes":["Bug","Story"],"screens":{"CREATE":"<screenId>"}},
 {"allIssueTypes":true,"screens":{"VIEW":"<screenId>"}}]}
```

- `mappings[]` 1–50: область (`issueTypes` из текущих типов проекта **или** `allIssueTypes:true`) и частичный патч `screens`; неуказанные операции сохраняют явный экран или `DEFAULT`.
- `issueTypes` меняет только названные типы; тип без своей строки получает явную строку. `allIssueTypes:true` меняет строку `DEFAULT` и все явные строки схемы проекта (в том числе типов не из проекта), новых строк не создаёт.
- Противоречия (один тип и операция с разными экранами, в том числе `allIssueTypes` против именной строки), неизвестный тип или экран — `VALIDATION_ERROR` для всего пакета. Одинаковые назначения допустимы.
- Итоговая матрица считается по одному исходному состоянию. Новая дочерняя Screen Scheme создаётся на каждую уникальную пару «исходная схема + меняемые операции»; исходные дочерние схемы, экраны и другие проекты не меняются, ничего не удаляется.
- `topScheme.mode` — по фактическим связям Jira, не по имени:
  - `UPDATE_PRIVATE` — нестандартная схема только этого проекта: строки правятся на месте, id, имя и связь сохраняются;
  - `COPY_SHARED` (схему используют другие проекты или исключительность не доказана) и `COPY_DEFAULT` (стандартная схема Jira): создаётся одна приватная схема, проект переключается на неё; следующий пакет пойдёт как `UPDATE_PRIVATE`;
  - `NO_CHANGE` — экраны уже совпадают: `ALREADY_ASSIGNED`.
  
  В превью `topScheme` содержит `mode`, `reason`, источник, `isDefault`, `otherProjects` (до 10) и только меняемые строки. Режим и все связи схемы, включая архивные проекты, входят в отпечаток.
- Ответ: `issueTypes[]` — по каждому типу проекта `operations[].before/after`, `selected`, `explicitMapping`, `changed`, схема до/после; `screenSchemes[]` — новые дочерние схемы (`appliesTo`, `newId`); `warnings` (влияние на `DEFAULT` и типы без своей строки); `matrixSource` (`PLANNED` в превью, `READ_BACK` после записи); `assignedSchemeId`.
- Сбой: `PARTIALLY_ASSIGNED` (что-то создано или изменено, либо состояние неизвестно) или `FAILED` (ничего), `errorCode:JIRA_ERROR`. `created` — фактически существующие объекты (`complete:false` — перечень неполон). `assignment`: для новой схемы `NOT_ASSIGNED`, `ASSIGNED` или `UNKNOWN`; для `UPDATE_PRIVATE` строки перечитываются — `ASSIGNED`, `NOT_ASSIGNED`, `PARTIAL` (расходящиеся строки «до / план / факт» в `warnings`) или `UNKNOWN`.

### `set_issue_type_screen_scheme_mappings`

Правит строки «тип задачи → Screen Scheme» **существующей** Issue Type Screen Scheme на месте — **для всех её проектов**. Копий нет, связи проектов не меняются, задачи не мигрируют. Для одного проекта — `set_project_operation_screens` или `clone_project_screens`.

```json
{"dryRun":true,"issueTypeScreenScheme":"<id | точное имя>","rows":[
 {"action":"ADD|REPLACE|REMOVE","issueType":"<id | точное имя | DEFAULT>","screenScheme":"<id | точное имя>"}]}
```

- 1–50 строк, не более одной на тип и на `DEFAULT`. `screenScheme` обязателен для `ADD`/`REPLACE` и запрещён для `REMOVE`.
- `ADD` — тип без своей строки; `ADD` существующей строки — предупреждение `ADD_AS_REPLACE` и `REPLACE`. `REPLACE` — своя строка или `DEFAULT`. `REMOVE` — тип переходит на `DEFAULT`; саму `DEFAULT` можно только заменить.
- Расчёт по итоговой таблице, порядок строк не важен. `rows[]`: тип, `requestedAction`/`action`, схема до и после (`viaDefault`), `change` (`CHANGE`/`NO_CHANGE`). Все `NO_CHANGE` — статус `NO_CHANGE`.
- `projects`: `linked` (N, все связанные, включая архивные), `changing` (M, где у какого-либо типа меняется Screen Scheme), `listed` — первые 10 по ключу (`key`, `name`, `archived`, `changedIssueTypes`, `rowTypesMissing`), `truncated`. `scopeText` называет N и M; проекты без изменения всё равно используют изменённую таблицу.
- Запись: сначала `DEFAULT`, затем строки по порядку, до первой ошибки. Итог по перечитанной таблице: `CHANGED`, `PARTIALLY_CHANGED`, `FAILED` (ничего не изменилось), `UNKNOWN`; по строкам — `APPLIED`, `NO_CHANGE`, `NOT_APPLIED`, `NOT_ATTEMPTED`, `UNKNOWN`.
- Отпечаток: строки, имя и описание схемы, связанные проекты и их типы, затронутые Screen Schemes.
- Ошибки: `ITSS_NOT_FOUND`, `ITSS_AMBIGUOUS`, `SCREEN_SCHEME_NOT_FOUND`, `SCREEN_SCHEME_AMBIGUOUS`, `ISSUE_TYPE_NOT_FOUND`, `DEFAULT_ROW_REQUIRED`, `ROW_NOT_FOUND`, `DUPLICATE_ROW`, `INVALID_ROW`, `VALIDATION_ERROR`; `errorRowIndex` с нуля.

### `set_screen_scheme_operations`

Правит строки «операция → экран» **существующей** Screen Scheme на месте — **для всех Issue Type Screen Schemes и проектов, которые её используют**. Id схемы сохраняется, копий нет, связи и задачи не меняются. Для одного проекта — `set_project_operation_screens` или `clone_project_screens`; сменить Screen Scheme типа — `set_issue_type_screen_scheme_mappings`.

```json
{"dryRun":true,"screenScheme":"<id | точное имя>","rows":[
 {"action":"ADD|REPLACE|REMOVE","operation":"DEFAULT|CREATE|EDIT|VIEW","screen":"<id | точное имя>"}]}
```

- 1–50 строк, не более одной на операцию. `screen` обязателен для `ADD`/`REPLACE` и запрещён для `REMOVE`. `ADD`/`REPLACE`/`REMOVE` — как у `set_issue_type_screen_scheme_mappings`; `REMOVE` переводит `CREATE`/`EDIT`/`VIEW` на экран `DEFAULT`.
- Расчёт по итоговой таблице (`REPLACE DEFAULT` + `REMOVE CREATE` — `CREATE` на **новом** экране `DEFAULT`). `rows[]`: операция, `requestedAction`/`action`, экран до и после (`viaDefault`), `change`.
- `usage`: Issue Type Screen Schemes со схемой (`linked`, первые 10 по id с `isDefault`, `truncated`). `projects`: `linked` N, `changing` M (где реально меняется эффективный экран), первые 10 по ключу (`key`, `name`, `archived`, `changedIssueTypes`, `issueTypeScreenScheme`), `scopeText`. Неиспользуемая схема — N = 0 и `NO_LINKED_PROJECTS`.
- `REQUIRED_FIELDS_MISSING_ON_CREATE` (не блокирует): для пар проект/тип со сменой экрана `CREATE` на новом экране нет обязательных полей (orderable, кастомные — только в контексте пары). `requiredFields`: `pairs`, до 10 `projects` с `issueTypes[]`, `truncated`, `fields`.
- Запись: явные `ADD`/`REPLACE` по порядку, затем `DEFAULT`, затем `REMOVE` — каждая операция всегда указывает на старый или новый экран; до первой ошибки. Итоги — как у `set_issue_type_screen_scheme_mappings`.
- Отпечаток: строки, имя и описание схемы, связанные ITSS и их строки, проекты и типы, экраны до и после, состав полей эффективных экранов `CREATE`. Не отслеживаются обязательность в field configuration и смена Field Configuration Scheme проекта (`limitations[]`).
- Ошибки: `SCREEN_SCHEME_NOT_FOUND`, `SCREEN_SCHEME_AMBIGUOUS`, `SCREEN_NOT_FOUND`, `SCREEN_AMBIGUOUS`, `INVALID_OPERATION`, `DEFAULT_ROW_REQUIRED`, `ROW_NOT_FOUND`, `DUPLICATE_ROW`, `INVALID_ROW`, `VALIDATION_ERROR`, `JIRA_ERROR` (в том числе схема без строки `DEFAULT`); `errorRowIndex` с нуля.

### `assign_issue_type_screen_scheme`

Переключает **один** проект на **существующую** Issue Type Screen Scheme. Меняется только связь проекта: схемы и экраны не правятся и не копируются, задачи не мигрируют.

```json
{"dryRun":true,"project":"<ключ | имя>","issueTypeScreenScheme":"<id | точное имя | DEFAULT>"}
```

- `DEFAULT` (ровно в верхнем регистре) — стандартная схема Jira, приоритетнее одноимённой. Архивный проект отклоняется.
- Ответ: `currentIssueTypeScreenScheme`, `newIssueTypeScreenScheme` (`id`, `name`, `isDefault`) и `issueTypes[]` — Screen Scheme и эффективные экраны `CREATE`/`EDIT`/`VIEW` до и после (`viaDefault`), `change`. Проект уже на схеме — `NO_CHANGE`.
- Предупреждения: `ITSS_ORPHANED` (прежняя схема остаётся без проектов, не удаляется), `SHARED_TARGET` (новую схему используют `count` других проектов — её правки затронут и этот), `REQUIRED_FIELDS_MISSING_ON_CREATE` (как у `set_screen_scheme_operations`).
- Итог по перечитанной связи: `CHANGED`; `FAILED` — связь осталась прежней (следы записи не исключены); `UNKNOWN` — состояние не установить. `observedIssueTypeScreenScheme` — перечитанная схема.
- Отпечаток: связь проекта, обе схемы с их Screen Schemes и экранами, типы проекта, проекты обеих схем, поля новых экранов `CREATE`. Обязательность и смена Field Configuration Scheme не отслеживаются (`limitations[]`).
- Ошибки: `ITSS_NOT_FOUND`, `ITSS_AMBIGUOUS` (с id схем), `UNKNOWN_PROJECT`, `VALIDATION_ERROR`, `JIRA_ERROR`.
- После переключения служебный откат `create_project` для этого проекта завершится конфликтом.

## Workflow

Глобального списка workflow нет: сценарии начинаются с `get_project_workflows` выбранного проекта или с точного имени workflow.

### `get_project_workflows`

Читает текущую конфигурацию проекта: схему workflow, `defaultWorkflow`, эффективный workflow каждого типа задач, черновики и совместное использование. Задачи не обходит.

- `{"project":"LAB"}` — `issueCountsStatus=NOT_REQUESTED`, `issues`/`statuses` равны `null`: неизвестное число не означает отсутствия задач.
- `{"project":"LAB","includeIssueCounts":true}` — полный подсчёт задач по типам и статусам, без ограничения по времени; `COMPLETE` включает типы, найденные только в задачах. Сбой подсчёта — `ERROR` и `issueCountsError`, конфигурация остаётся в ответе.

Ни один режим не доказывает безопасность миграции: эти проверки и число переносимых задач даёт превью `assign_workflow`.

### `get_workflow`

Читает одну версию workflow: `{"workflow":"<точное имя>","version":"ACTIVE"|"DRAFT"}`. Для неактивного workflow `ACTIVE` — сохранённая версия. Отсутствующий черновик — ошибка, другая версия не подставляется.

Ответ:
- `revision` — вход для `configure_workflow.existing.expectedRevision`; охватывает активную версию, черновик, активность и описание;
- steps: `stepId`, `statusId`, имя, категория и описание статуса, `initial`;
- transitions: `actionId`, `kind` (`INITIAL`, `LOCAL`, `GLOBAL`, `COMMON`), `sourceStepIds`, `targetStepIds`, `screenId`, правило Resolution (`SET`, `CLEAR`, `PRESERVE`, `UNKNOWN` с причиной), `unsupported` — какие правки перехода будут отклонены;
- `capabilities`; `consumers` — схемы (включая черновики схем), проекты и типы задач, полные счётчики и списки до 10; `draftDiff` — активная версия → черновик.

XML и аргументы сторонних функций не возвращаются. Граф больше 1000 шагов и переходов отклоняется целиком.

### `get_workflow_validators`

Читает validators всех переходов одной версии: `{"workflow":"<точное имя>","version":"ACTIVE"|"DRAFT"}`. Ответ: `revision` (как у `get_workflow`), `scriptRunner {installed, enabled, version, supported, reason}` и `transitions[]` (`actionId`, `name`, `screenId`/`screenName`, `validators[]` в порядке descriptor). Виды:
- `REQUIRED_FIELDS` — «Field(s) required» ScriptRunner в точной форме: `fieldIds`, `fields[{id, name}]`, `conditional`;
- `COMMENT_REQUIRED` — «Comment required» в точной форме, без аргументов;
- `OTHER` — всё прочее: только `type`, `className`, `cannedScript`.

У каждого validator есть `problem`: `null` или, для `COMMENT_REQUIRED`, диагноз «у перехода нет пригодного экрана — ScriptRunner завершит переход ошибкой» (экран не назначен или не существует, id не число, нет `view="fieldscreen"`). Чтение работает без ScriptRunner.

### `get_workflow_conditions`

Читает conditions всех переходов одной версии: тот же вход, `revision`, `scriptRunner` и `transitions[]` (`actionId`, `name`, `root`). `root` — `null` или дерево в порядке descriptor. Узел: `position` (с 1 внутри родителя) и `kind`:
- `COMPOUND` — `operator` `AND`/`OR`, `children[]`; корень без типа — `AND`;
- `GROUP` — `UserInGroupCondition` Jira: `group`;
- `PROJECT_ROLE` — «Project role(s) condition» ScriptRunner: `roleIds[]`, `roles[{id, name}]` (`name:null` — роли нет), `inverted`;
- `OTHER` — всё прочее, включая эти условия в иной форме: `type`, `className`, `cannedScript`.

Ключи, не относящиеся к виду узла, равны `null`. Совместимость ScriptRunner для условий проверяется отдельно от validators: сейчас подтверждена только версия 9.46.0, и `scriptRunner.supported` здесь относится к «Project role(s) condition». Чтение работает без ScriptRunner.

### `configure_workflow`

Одна правка **одного** workflow: одно превью полного изменения, одно подтверждение, одна операция. Подробное устройство (на английском) — [Configure-workflow-how-it-works](howitworks/mcp/Configure-workflow-how-it-works.md). Ровно одна цель:

- `create:{name, description?, statuses, initialStatus, transitions}` — новый workflow из текста или изображения графа; у каждого перехода уникальный `key`.
- `existing:{workflow, version:"ACTIVE"|"DRAFT", expectedRevision, changes?}` — существующий workflow; `expectedRevision` — из `get_workflow`. `changes` — строгий массив 1–100; `null`, `[]`, null-элементы и неизвестные поля отклоняются.

Общие блоки: `newScreens:[{key, name, description?, fieldIds}]` и `transitionRules:[{transitionKey | actionId, screen?, addValidators?, addConditions?}]`, один блок на переход. Схема закрытая: неизвестные свойства, `null` вместо блоков и произвольные script/class/args не принимаются. Отсутствие или пустые `newScreens`/`transitionRules` ничего не меняют; если не меняется ничего — `UNCHANGED`.

```json
{
  "dryRun": true,
  "create": {
    "name": "Delivery",
    "statuses": [{"name":"To Do"},{"name":"In Progress"}],
    "initialStatus": "To Do",
    "transitions": [{"key":"start","name":"Start","from":"To Do","to":"In Progress"}]
  },
  "newScreens": [{"key":"form","name":"Delivery form","fieldIds":["description"]}],
  "transitionRules": [{
    "transitionKey": "start",
    "screen": {"action":"SET","screenKey":"form"},
    "addValidators": [
      {"template":"comment_required"},
      {"template":"required_fields","parameters":{"fieldIds":["description"]}}
    ],
    "addConditions": {"operator":"AND","rules":[
      {"template":"group","parameters":{"group":"delivery-users"}},
      {"template":"project_role","parameters":{"roles":["Developers"]}}
    ]}
  }]
}
```

**Граф `create`.** Статусы с совпадающим именем (без учёта регистра) переиспользуются, отсутствующие создаются с категорией. `initialStatus` обязателен. Глобальный переход `{"key":"reopen","name":"Reopen","fromAny":true,"to":"To Do"}` действует из всех статусов, включая целевой; петли поддерживаются. «All/Все» на диаграмме — источник, а не имя кнопки; без подписи имя перехода — имя цели. Существующий экран можно указать в `create.transitions[].screen` (id или имя). Начальный переход Create строит Jira, в запросе он не адресуется.

Занятое имя даёт `EXISTS` только при полностью совпадающем графе (включая `view` каждого перехода: экрану нужен `fieldscreen`) **без** правил и экранов в запросе; иначе `NAME_CONFLICT`. Существующий workflow через `create` не настраивается.

**Правки `existing.changes`:** `ADD_STATUS`, `REPLACE_STATUS`, `REMOVE_STATUS`, `SET_INITIAL_STATUS`, `ADD_TRANSITION`, `UPDATE_TRANSITION`, `REMOVE_TRANSITION`, `UPDATE_METADATA`. Все правки, затем `transitionRules` применяются к копии descriptor, проверяется итоговый граф — один diff, один отпечаток, одна запись. Существующие id, неназванные правила, свойства и порядок сохраняются.

- **Шаги** адресуются `stepId`. `ADD_STATUS` — ровно одно: `statusId` существующего глобального статуса или `newStatus:{name, category, description?}`. Совпадение имени (trim, без учёта регистра) с одним статусом — переиспользование без изменения статуса (несовпавшая категория — предупреждение); несколько — `AMBIGUOUS_STATUS` с кандидатами; нет — создание глобального статуса. Превью различает CREATE/REUSE. Статус, уже присутствующий в workflow, и дубли отклоняются.
- `ADD_STATUS` может задать локальный `clientKey`. `source:{stepId | clientKey | fromAny:true}`, `targetStepId`/`targetClientKey` и начальный шаг могут ссылаться на новый шаг в том же запросе в любом порядке.
- **Переходы** адресуются прочитанным `actionId`. Только `ADD_TRANSITION` задаёт `transitionKey` (непустая строка до 255 символов без пробелов по краям, уникальна в запросе с учётом регистра). В `transitionRules` для `existing` нужно ровно одно: `actionId` существующего перехода или `transitionKey` нового. Удалённые, неизвестные и угаданные id, неизвестные ключи и повтор цели отклоняются. Ключи переходов, `clientKey` шагов и `newScreens[].key` — независимые пространства.
- **Экран перехода** в `changes`: `screen:{action:"SET", screenId}` или `{action:"CLEAR"}` (CLEAR снимает ссылку и только view `fieldscreen`). Явный SET/CLEAR одного перехода и в `changes`, и в `transitionRules` — конфликт, даже при одинаковом значении.
- **Resolution:** `resolution:{action:"SET", value}` / `{action:"CLEAR"}` / `{action:"PRESERVE"}` меняет только штатную post-function для будущих переходов; задачи не исправляются. Вход в DONE требует SET; CLEAR — только в не-DONE. Нестандартный или условный писатель — `UNSUPPORTED_DESCRIPTOR`. При смене источника или цели противоречие Resolution нужно разрешить в том же запросе.
- `UPDATE_METADATA` меняет только описание.
- **Замена и удаление статусов.** Замена статуса шага активной версии — `MIGRATION_REQUIRED`, даже без задач. Удаление шага активной версии требует, чтобы в нём не было задач во всех проектах workflow (проверяется в превью и перед публикацией). Ссылки, включая начальный шаг, переназначаются явно, без каскада; глобальный статус не удаляется. Для занятого шага: `copy_workflow` → `configure_workflow` копии → `assign_workflow` со `statusMappings`.
- Ограничения: у переходов INITIAL/COMMON/conditional и join/split источник, цель и Resolution не меняются; COMMON — только имя и экран. Встроенный workflow `jira` — `SYSTEM_WORKFLOW`. Структурные правки не требуют ScriptRunner.

```json
"existing": {"workflow":"Delivery","version":"ACTIVE","expectedRevision":"<из get_workflow>","changes":[
  {"op":"ADD_STATUS","clientKey":"doing","newStatus":{"name":"Doing","category":"IN_PROGRESS"}},
  {"op":"ADD_TRANSITION","transitionKey":"enter","name":"Start","source":{"stepId":1},"targetClientKey":"doing"}]}
```

Вместе с этим можно передать `newScreens` и `transitionRules` с `transitionKey:"enter"`. После успешного перечитывания результат возвращает `transitions` (`transitionKey → actionId`) и `objects` (`clientKey → stepId`, `newScreens.key → screenId`). Id из превью — плановые и в том же запросе как `actionId` не принимаются. При `FAILED`/`PARTIAL`/`UNKNOWN` фактически созданные id берутся из квитанции выполнения.

**Правила только добавляются.** Существующие validators и conditions, их порядок и посторонние свойства сохраняются; удаление, замена и перестановка не поддерживаются.
- `required_fields`: недостающие поля добавляются **отдельным** validator в конец; покрытие считается по всем безусловным распознанным validators.
- `comment_required` пропускается только при точной существующей форме.
- Повтор `group` (без учёта регистра) или того же набора ролей с тем же `invert` распознаётся только у прямого листа корневого AND.
- Нераспознанная форма запрошенного правила — `UNSUPPORTED_DESCRIPTOR`; прочие нераспознанные правила сохраняются.

**Conditions.** Нет корня — создаётся AND; к AND новые листья добавляются в конец. Корень OR или неизвестный — отказ. Нужно выполнить **все** условия.
- `group` — член существующей группы Jira, без ScriptRunner. Пустая сейчас группа — предупреждение: этому условию никто не удовлетворит.
- `project_role` — член **любой** из ролей (до 20 id или точных имён; неоднозначное — `AMBIGUOUS_ROLE`) в проекте задачи; `invert:true` — ни одной.
- Validators и `project_role` требуют включённый ScriptRunner проверенной версии (9.46.0).
- Условия сужают доступ для **всех** проектов workflow.

**Экраны.** `transitionRules[].screen:{action:"SET", screenId | screenName | screenKey}` ставит ссылку и view `fieldscreen` вместе; `"KEEP"` ничего не проверяет. Неоднозначное имя — `AMBIGUOUS_SCREEN`. Новый экран объявляется в `newScreens` (одна вкладка `Field Tab` с существующими orderable-полями по порядку) и используется через `screenKey`; общий ключ на нескольких переходах — один экран. Повторы, неизвестное поле или ключ, неиспользуемый экран — отказ. Одноимённый существующий экран не переиспользуется (предупреждение).

**Итоговая проверка `existing`** охватывает добавляемые переходы, переходы с явным SET/CLEAR и цели непустых `addValidators`/`addConditions`. `comment_required` без пригодного экрана — `TRANSITION_SCREEN_MISSING`; поле `required_fields`, которого нет на экране, — предупреждение; нераспознанные validators — предупреждение «не анализировались». Прочие переходы старыми дефектами не блокируются.

**Виды переходов.** LOCAL/GLOBAL — правила и экран; COMMON — только экран (превью показывает все шаги); INITIAL — правила без экрана.

**Resolution в `create`.** Вход в DONE из другого статуса или `fromAny` требует `{"action":"SET","value":"<id из get_resolutions>"}`, переход между разными DONE — тоже SET. Выход из DONE и `fromAny` в не-DONE — автоматически CLEAR; прочие не-DONE переходы сохраняют значение. Явный CLEAR в DONE запрещён. Превью показывает итоговые SET/CLEAR/PRESERVE.

**Версии.** Неактивный workflow (`ACTIVE`) сохраняется на месте (`UPDATED_INACTIVE`); активный без черновика — создаётся черновик (`DRAFT_SAVED`), с черновиком — `DRAFT_EXISTS`. `DRAFT` требует существующий черновик и сохраняет его. Публикации и назначения проекту нет (`publish_workflow_draft`, `assign_workflow`). Превью перечисляет итоговые ограничения переходов, обязательные поля и комментарий, дерево AND, экраны, создаваемые глобальные объекты, проекты workflow и общие эффекты. Ошибка любого правила или справочника — без плана. После сбоя экран может остаться незавершённым или неназначенным; partial/`UNKNOWN` не запускают откат или повторное создание.

### `copy_workflow`

Точная копия основной (активной или сохранённой) версии одного workflow, включая встроенный `jira`.

```json
{"dryRun":true,"workflow":"<точное имя источника>","name":"<новое имя>","description":"<необязательно>"}
```

- Имя обрезается по краям, до 255 символов; занятое без учёта регистра — `NAME_TAKEN`. Без `description` наследуется описание источника.
- Копия неактивна и не назначена ни одной схеме; источник, проекты и задачи не меняются. Черновик источника не копируется: `sourceHasDraft:true` и предупреждение — опубликуйте его заранее, если нужен.
- Путь для правок, недоступных в черновике активного workflow: `copy_workflow` → `configure_workflow` копии → `assign_workflow` со `statusMappings`. Это новый workflow, не переименование.
- `CREATED` включает число steps и transitions (без начального Create) и `report.created`. Определение копии после записи отличается от источника (кроме описания) — `FAILED`/`JIRA_ERROR`: копия могла остаться, проверьте Jira и журнал.
- Служебный откат возможен, пока копия не изменена, не назначена и не используется задачами.
- Ошибки: `VALIDATION_ERROR`, `UNKNOWN_WORKFLOW`, `NAME_TAKEN`, `PREVIEW_STALE`, `JIRA_ERROR`.

### `assign_workflow`

Подключает существующий workflow к проекту — ко всем типам задач (`allIssueTypes:true`) или к названным (`issueTypes`) — и переводит задачи на него. Задача сохраняет статус, если он есть в целевом workflow; поля и история сохраняются.

```json
{"dryRun":true,"project":"ABC","workflow":"Bug flow","issueTypes":["Bug"],
 "statusMappings":[{"from":"In Review","to":"In Progress"}]}
```

- Занятый задачами статус, которого нет в целевом workflow, требует явного `statusMappings`; пока не сопоставлен каждый такой статус, весь вызов — `STATUS_CONFLICT` без записи (блокирующие статусы и число задач — в `issueTypes[].statuses` с `inWorkflow=false`).
- Превью по типу задач: `ALREADY` (уже назначен), `READY` (задач нет), `MIGRATE` (задачи переносятся); действие со схемой, число задач, предупреждения. Всё уже назначено — `ALREADY_ASSIGNED`.
- Собственная схема проекта меняется на месте; проект на стандартной схеме получает новую. Схему используют другие проекты — `SCHEME_SHARED`; `newScheme=true` даёт проекту свою схему.
- Не больше 10 000 переносимых задач за вызов (`TOO_MANY_ISSUES` — разбейте по типам). Выполняется синхронно, большой проект — минуты. Вызовы не пересекаются: при занятости повторите тот же запрос позже.
- Прочие ошибки: `DRAFT_EXISTS` (черновик схемы; опубликуйте или отбросьте его в Jira либо `newScheme=true`), `UNKNOWN_WORKFLOW`, `UNKNOWN_PROJECT`, `UNKNOWN_ISSUE_TYPE`, `ISSUE_TYPE_NOT_IN_PROJECT`, `JIRA_ERROR`.

Итоги:
- `ASSIGNED` — схема назначена, перенос и финальный проход по задачам завершены;
- `PARTIALLY_ASSIGNED` — назначение подтверждено чтением, но финальный проход не перенёс или не проверил задачи, созданные во время вызова, либо ошибка пришла после назначения;
- `UNKNOWN` — запись могла начаться, назначение не подтверждено;
- `FAILED` — ошибка до первой записи.

Для `PARTIALLY_ASSIGNED` и `UNKNOWN` `message` перечисляет подтверждённое (схема, число перенесённых задач, назначение) и неподтверждённое. Состояние выясняется через `get_project_workflows` и новое превью.

### `clone_project_workflows`

Даёт одному проекту собственную копию общей настройки workflow: точную копию схемы и, по выбору, самих workflow (шаги, переходы, conditions, validators, post-functions и свойства, включая настройки других приложений). Оригиналы не меняются, другие проекты ничего не замечают; задачи переходят на копии с теми же статусами.

```json
{"dryRun":true,"project":"ABC","scope":"SELECTED","workflows":["Bug flow"]}
```

- `scope`: `SCHEME_ONLY` — только схема, workflow остаются общими; `ALL_WORKFLOWS` — схема и каждый workflow, который используют и другие проекты; `SELECTED` — схема и названные `workflows`. Вердикты: `COPY`, `KEEP`, `ALREADY_OWN` (workflow уже только этого проекта).
- Собственная схема проекта меняется на месте (`scheme.action:UPDATE`), иначе создаётся новая (`CREATE`). Ничего общего не осталось — `ALREADY_SEPARATE`.
- Имена копий: ключ другого проекта в начале имени заменяется ключом этого (`BUS: Process scheme` → `SPMT: Process scheme`), иначе добавляется префикс `KEY: `; ` v2`, ` v3` — только при занятом имени. Свои имена — `schemeName` и `workflowNames`.
- Копия строится из основной версии workflow; черновик не копируется и не меняется. Превью (`WOULD_CLONE`) одним предупреждением называет копируемые workflow с черновиком; появление или исчезновение черновика до применения — `PREVIEW_STALE`.
- Ошибки: `DRAFT_EXISTS` (черновик **схемы** workflow — опубликуйте или отбросьте его), `WORKFLOW_NOT_IN_SCHEME`, `UNKNOWN_WORKFLOW`, `UNKNOWN_PROJECT`, `NAME_TAKEN`, `TOO_MANY_ISSUES` (до 10 000 задач за вызов), `JIRA_ERROR`. Блокировка общая с `assign_workflow`.
- Ошибка после записи: `PARTIALLY_CLONED` (чтение подтверждает, что проект использует копии) или `UNKNOWN`; `message` перечисляет созданные копии, число перенесённых задач и неподтверждённое.

### `publish_workflow_draft`

Публикует черновик одного активного workflow штатной публикацией Jira. Черновик становится рабочей версией для **всех** проектов и схем этого workflow. Прежняя активная версия не сохраняется.

- Превью `{"dryRun":true,"workflow":"Lot Buy"}`: `changes` (добавленные и удалённые статусы и переходы, изменённые переходы, `descriptorChanged`), `affectedProjects`, `affectedProjectNames`, `affectedSchemes` и `PUBLISH_REFUSED`, если Jira не примет черновик. Нет черновика — `NO_DRAFT`.
- Для шагов, которых нет в черновике, считаются задачи в них по всем проектам: есть задачи — `PUBLISH_REFUSED` с числами. Ноль входит в отпечаток: новая задача в таком шаге до применения — `PREVIEW_STALE`. Штатные проверки Jira выполняются и не обходятся.
- После `PUBLISHED` черновика нет, активная версия совпадает с подтверждённой. Прежний план `clone_project_workflows` устаревает. Неподтверждённый результат — `JIRA_ERROR`; повторять публикацию не нужно.

### `rename_workflow`

```json
{"dryRun":true,"workflow":"<имя>","newName":"<новое имя>"}
```

Штатное переименование Jira, как в администрировании: workflow сохраняется под новым именем, ссылки схем (включая их черновики) переписываются, определение не меняется. Jira переименовывает только неактивный workflow. Отказы: `ACTIVE_WORKFLOW`, `SYSTEM_WORKFLOW`, `IN_USE` (у задач есть записи workflow со старым именем), `NAME_TAKEN`, `RENAME_REFUSED` (причина Jira, например черновик). `RENAMED` подтверждается чтением обоих имён, определения и схем. Копия с назначением (`copy_workflow` + `assign_workflow`) — другая операция.

### `update_status`

```json
{"dryRun":true,"statusId":"<id>","name":"…","description":"…","category":"TO_DO|IN_PROGRESS|DONE"}
```

Меняет **глобальный** статус: изменение видно во всех workflow (активных, неактивных, черновиках), проектах и задачах с ним. Отсутствующее свойство сохраняется, `description:""` очищает. Превью перечисляет такие workflow и проекты (полные счётчики, имена до 10). Имя проверяет Jira (`UPDATE_REFUSED`). Смена категории не меняет Resolution задач и правила переходов; переименование ломает сохранённый JQL со старым именем. Статуса «только для проекта» и удаления статуса нет.

### `list_statuses`

Ищет статусы: `id`, `name`, категория (`TO_DO`, `IN_PROGRESS`, `DONE`, `UNDEFINED`), описание.
- `query` — до `limit` записей (по умолчанию 20, максимум 100) из `total`, описания до 200 символов.
- `names` — до 30 точных имён одним вызовом: `lookup:[{name, result: FOUND|NOT_FOUND|AMBIGUOUS, statuses}]` в порядке запроса.

Для `configure_workflow` не обязателен: превью само переиспользует существующие статусы по имени.

### `get_resolutions`

Возвращает существующие Resolution (`id`, `name`, `description`). `query` — точный id или имя без учёта регистра. Id для `SET` в `configure_workflow` берётся только отсюда. «Done» не означает `Fixed` по умолчанию.

### `create_resolution`

Создаёт глобальное значение Resolution для **всей Jira**. Превью: `name`, необязательное `description`.

- Те же имя и описание — `EXISTS` с существующим id. Неоднозначное имя или другое описание — `NAME_CONFLICT` без перезаписи. `Unresolved`/«Нерешено» запрещены: это отсутствие значения.
- Resolution и workflow — отдельные операции: после создания нужно новое превью workflow с реальным id; при сбое workflow значение остаётся.
- Служебный откат не удаляет изменённое, используемое задачами или workflow либо установленное по умолчанию значение.

## Типы задач

### `list_issue_types`

Ищет типы задач по id или имени: `id`, `name`, `description`, `subtask`. `query` и `limit`, `total`; по умолчанию 20 записей, максимум 100, описания до 200 символов.

### `create_issue_types`

Создаёт или переиспользует **пакет** глобальных типов задач одним планом; один тип — пакет из одного.

```json
{"dryRun":true,"issueTypes":[
 {"key":"pay","name":"Оплата","subtask":false},
 {"key":"check","name":"Проверка","description":"Подзадача проверки","subtask":true}]}
```

- `issueTypes` 1–20: `key` — локальная метка (1–40 символов `A-Za-z0-9_.-`, не ключ Jira), `name`, `description?`, явный `subtask`.
- Совпадение имени (без учёта регистра) с одним типом того же вида — `EXISTS`, тип не меняется; нет совпадения — `WOULD_CREATE`. Дубли `key` или имён, неоднозначное имя, имя типа другого вида, пропущенные поля, больше 20 элементов — пакет `FAILED` (`VALIDATION_ERROR` или `NAME_CONFLICT`) с отмеченными элементами. Всё уже существует — `EXISTS` без плана.
- Отпечаток (решения по элементам и весь каталог типов) сверяется один раз перед первой записью. Во время пакета проверяются только занятость имени следующего типа и целостность переиспользуемых. Для нескольких типов используйте один пакет: два отдельных превью друг с другом не согласуются.
- **Где id:** `result.results[]` в порядке запроса: `key`, `status` (`CREATED`, `EXISTS`, `FAILED`, `UNKNOWN`, `NOT_EXECUTED`), `issueType.id`; те же id — в `result.report.created`/`reused` (`kind: ISSUE_TYPE`). Дальше используйте только id со статусом `CREATED` или `EXISTS`.
- Итог: `CREATED` — созданы все отсутствовавшие; `PARTIALLY_CHANGED` — часть создана и остаётся в Jira и/или исход одной записи `UNKNOWN`, остальные `NOT_EXECUTED`; `FAILED` — ничего не записано. Повторное превью того же списка планирует только недостающее.
- **Неизвестный исход.** Если запись бросила исключение, каталог перечитывается: тип не найден — `FAILED`; каталог не прочитан — `UNKNOWN` без id; найден тип с тем же именем и видом — тоже `UNKNOWN` (его мог создать кто-то другой): id только наблюдаемый, в `report.created` не попадает и автоматически не удаляется.
- Служебный откат затрагивает только подтверждённо созданные типы, с проверками использования; переиспользованные и неизвестного происхождения не удаляются.
- Jira добавляет каждый новый тип в стандартную схему типов, поэтому он виден всем её проектам. Схемы проектов не переназначаются, задачи не меняются.

### `set_project_issue_types`

Задаёт **полный итоговый** набор типов задач проекта: `issueTypes` (существующие, до 500), необязательные `newIssueTypes` (как в `create_project`: переиспользуются при совпадении имени и вида, иначе создаются в той же операции) и `defaultIssueType` (локальный `key` или стандартный тип набора). Без `defaultIssueType` сохраняется прежний default, если он остаётся в наборе, иначе — первый стандартный тип.

- Типы вне набора убираются из проекта, но не из Jira.
- Есть задачи удаляемого типа — нужен `mappings` (`from` → `to`, цель — тип набора). Обычные задачи — только в обычный тип, подзадачи — только в подзадачи.
- У целевого типа другой workflow без занятого статуса — `STATUS_CONFLICT`: нужен `statusMappings` для этого типа.
- Превью: добавляемые и удаляемые типы, число переносимых задач, исходный и целевой workflow, преобразования статусов; с `newIssueTypes` — `issueTypeSet` (создание/переиспользование, default, влияние).
- Общая или стандартная схема типов не меняется: проекту создаётся своя. Собственная схема проекта обновляется на месте.
- С `newIssueTypes` сбой — результат, а не исключение: `PARTIALLY_CHANGED` (часть типов создана и остаётся либо исход `UNKNOWN`), набор не назначается (`assignment.state NOT_STARTED`) или назначение прервано (`PARTIALLY_CHANGED`/`UNKNOWN` с `execution`). Повторное превью переиспользует созданное.

## Операции

### `start_operation`

Запускает один подтверждённый план в фоне: `toolName` и `arguments:{dryRun:false, planId}`. `sessionId` и `changeSetId` не передаются: владением управляет сервер (явная пара должна принадлежать пользователю и быть открытой). Разрыв соединения работу не останавливает. Восстановление — см. [протокол](#протокол-операций-и-восстановления).

### `get_operation`

Читает состояние и доменный результат своей операции по `operationId`, в том числе после переподключения. `waitSeconds` — 0–20 (по умолчанию 20): сервер ждёт до этого срока и отвечает сразу при конечном состоянии; `0` — немедленное чтение. При `QUEUED`, `RUNNING`, `CANCEL_REQUESTED` вызывайте снова, без пауз и внешних запросов.

## Отчёт о созданном (`report`)

Семь create-инструментов (`create_project`, `create_custom_fields`, `create_screen`, `create_issue_types`, `create_resolution`, `create_field_configuration`, `create_field_configuration_scheme`) возвращают в результате применения блок `report` со ссылками на созданное. В превью его нет. Созданное удаляется вручную по ссылкам. `configure_workflow` возвращает id в своём результате и квитанции выполнения.

```json
"report": {
  "created": [{"kind":"CUSTOM_FIELD","id":"customfield_10201","name":"Severity","url":"http://host/jira/secure/admin/ConfigureCustomField!default.jspa?customFieldId=10201"}],
  "reused":  [{"kind":"STATUS","id":"3","name":"In Progress","url":"http://host/jira/secure/admin/EditStatus!default.jspa?id=3"}]
}
```

- `created` — созданное инструментом; `reused` — найденное и использованное (например, существующее поле в `create_custom_fields` и `create_screen`). Прочитанное, но не использованное, не попадает. Системные поля экрана не входят.
- `kind`: `PROJECT`, `CUSTOM_FIELD`, `FIELD_CONTEXT`, `OPTION`, `SCREEN`, `SCREEN_TAB`, `WORKFLOW`, `STATUS`, `ISSUE_TYPE`, `RESOLUTION`, `FIELD_CONFIGURATION`, `FIELD_CONFIGURATION_SCHEME`.
- Опции не перечисляются: одна запись `OPTION` на контекст со счётчиком `count` (с дочерними) и ссылкой на опции контекста.
- `url` строится от канонического base URL Jira (с context path, например `/jira`). Нет надёжной страницы — `url` нет (так у `FIELD_CONFIGURATION*`). У workflow `id` пуст, страница ищется по имени. `FIELD_CONTEXT` ведёт на контексты поля, `SCREEN_TAB` — на экран, `PROJECT` — на `/plugins/servlet/project-config/<KEY>`.

## Названия инструментов в клиенте

Инструменты передают русские заголовки в `annotations.title` (например, `list_projects` — «Поиск проектов Jira»). Клиент может показывать заголовок или техническое имя. Плагин не скрывает карточки вызовов и не управляет запросами разрешений клиента; `readOnlyHint: true` само по себе автоподтверждение чтения не включает.

После обновления плагина переподключите MCP-сервер, чтобы клиент заново получил каталог инструментов.
