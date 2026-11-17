Статусы:
- QA Open — TO_DO, начальный;
- QA Working — IN_PROGRESS;
- QA Done — DONE.

Резолюции: используй существующие Fixed и Duplicate.
Сначала проверь каталог резолюций. Если этих значений нет,
ничего не создавай: покажи доступные и попроси выбрать замену.

Переходы:
1. Start: QA Open → QA Working.
2. Repeat work: QA Working → QA Working.
3. Finish: QA Working → QA Done, установить Fixed.
4. Reject: QA Working → QA Done, установить Duplicate.
5. Keep outcome: QA Done → QA Done, резолюцию сохранить.
6. Change outcome: QA Done → QA Done, установить Duplicate.
7. Reopen: QA Done → QA Open, очистить резолюцию.
8. Global finish: из любого статуса → QA Done,
   включая QA Done, установить Fixed.
9. Global reopen: из любого статуса → QA Open,
   включая QA Open, очистить резолюцию.
