// The one existing standard issue type a live scenario works with. Chosen explicitly (LIVE_ISSUE_TYPE): there is no
// default and no fallback, because the type names of a Jira are its own (a Russian install has «Задача», not «Bug»)
// and a scenario must never create a global type just to find one.

export const ISSUE_TYPE_ENV = 'LIVE_ISSUE_TYPE';

const label = (type) => `«${type.name}» (id ${type.id})`;

/**
 * Resolves `wanted` (an id, or an exact name compared without case) to one issue type of `types` (the answer of
 * GET /rest/api/2/issuetype). An id wins over a name. Throws, with what to do, when nothing is chosen, nothing or
 * several types match, the type is a subtask, or its name is shared with another type (scenarios and their checks
 * identify the type by name).
 */
export function pickIssueType(types, wanted) {
  const value = String(wanted ?? '').trim();
  if (!value) {
    throw new Error(`${ISSUE_TYPE_ENV} не задан: укажите id или точное имя существующего стандартного (не подзадача) типа `
      + `задач этой Jira, например ${ISSUE_TYPE_ENV}=10000. Значения по умолчанию нет, новый глобальный тип сценарий не создаёт.`);
  }
  let found = types.filter((type) => String(type.id) === value);
  if (found.length === 0) found = types.filter((type) => String(type.name).toLowerCase() === value.toLowerCase());
  if (found.length === 0) {
    throw new Error(`тип задач «${value}» не найден ни по id, ни по точному имени; в этой Jira: `
      + `${types.filter((type) => !type.subtask).map(label).join(', ') || '(нет стандартных типов)'}`);
  }
  if (found.length > 1) {
    throw new Error(`имя «${value}» неоднозначно (${found.map(label).join(', ')}): задайте id в ${ISSUE_TYPE_ENV}`);
  }
  const [type] = found;
  if (type.subtask) throw new Error(`тип ${label(type)} — подзадача; нужен стандартный тип`);
  const sameName = types.filter((other) => String(other.name).toLowerCase() === String(type.name).toLowerCase());
  if (sameName.length > 1) {
    throw new Error(`имя типа ${label(type)} носят несколько типов (${sameName.map(label).join(', ')}): сценарии находят тип по `
      + 'имени, такой тип не подходит');
  }
  return { id: String(type.id), name: type.name };
}

/** Reads the types through `rest(method, path)` (read only) and resolves `wanted`. */
export async function resolveIssueType(rest, wanted) {
  return pickIssueType(await rest('GET', '/rest/api/2/issuetype'), wanted);
}
