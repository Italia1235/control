import { applies } from '../../checks.mjs';
import { OPERATION_KEY, readLayout } from './setup.mjs';

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const flat = (screen) => screen.tabs.flatMap((tab) => tab.fields);
const WRITES_TO_SCREENS = ['edit_screen_layout', 'remove_field_from_screens', 'add_field_to_screens'];

/** Screen ids a screen-content write addresses, from the previewed arguments. */
function targetScreens(args) {
  if (args?.screenId) return [args.screenId];
  return (args?.screens ?? []).map((screen) => screen.screenId);
}

export default async function verify({ prepared, trace, tool }) {
  const { main, other, fieldIds: [f1, f2, f3], main_layout: before, other_layout: otherBefore } = prepared;
  const checks = [];
  const sharedScreens = new Set(Object.keys(before.contents).map(Number));

  // Other project: scheme, operation mapping and every screen's tabs/field order exactly as set up.
  const otherNow = await readLayout(tool, other);
  const identity = (layout) => ({ ...layout, scheme: { id: layout.scheme.id, name: layout.scheme.name } });
  checks.push({ name: `${other}: схема, экраны и их содержимое не изменились`,
    ok: same(identity(otherNow), identity(otherBefore)), detail: `схема ${otherNow.scheme.id}, экранов ${Object.keys(otherNow.contents).length}` });
  // The shared screens themselves (reached through the other project) carry the snapshot layout, i.e. never edited.

  const now = await readLayout(tool, main);
  const ownScheme = now.scheme.id !== prepared.scheme.id && !now.scheme.isDefault && now.scheme.otherProjects === 0;
  const ownScreens = Object.keys(now.contents).every((id) => !sharedScreens.has(Number(id)));
  checks.push({ name: `${main}: собственная схема экранов и собственные экраны`,
    ok: ownScheme && ownScreens,
    detail: `схема ${now.scheme.id} (была ${prepared.scheme.id}), другие проекты на ней: ${now.scheme.otherProjects}, экраны ${Object.keys(now.contents).join(',')}` });

  // Layout per operation: EDIT changed as asked, CREATE and VIEW identical to the snapshot.
  const expectedEdit = ['summary', f3, 'description', f1];
  for (const [index, entry] of now.operations.entries()) {
    const snapshot = before.operations[index];
    const screen = (op) => now.contents[entry.screens[OPERATION_KEY[op]]];
    const original = (op) => before.contents[snapshot.screens[OPERATION_KEY[op]]];
    const edit = screen('EDIT');
    checks.push({ name: `${main}, ${entry.issueType}: EDIT — «f3» сразу после Summary, «f2» нет, остальные в прежнем порядке`,
      ok: Boolean(edit) && same(flat(edit), expectedEdit) && !flat(edit).includes(f2),
      detail: JSON.stringify(edit && flat(edit)) });
    for (const op of ['CREATE', 'VIEW']) {
      const screenNow = screen(op);
      checks.push({ name: `${main}, ${entry.issueType}: ${op} как в снимке`,
        ok: Boolean(screenNow) && same(screenNow.tabs, original(op).tabs),
        detail: JSON.stringify(screenNow && flat(screenNow)) });
    }
    const ids = ['CREATE', 'EDIT', 'VIEW'].map((op) => entry.screens[OPERATION_KEY[op]]);
    checks.push({ name: `${main}, ${entry.issueType}: у операций по-прежнему три разных экрана`,
      ok: ids.every(Boolean) && new Set(ids).size === 3, detail: ids.join(',') });
  }

  // Protocol: detach first, then edit only copies of the main project's screens; nothing for the other project.
  const done = applies(trace);
  const position = (predicate) => done.findIndex(predicate);
  const argsOf = (apply) => apply.preview?.arguments;
  const detach = (apply) => apply.toolName === 'clone_project_screens' && argsOf(apply)?.project === main;
  const firstDetach = position(detach);
  const firstEdit = position((apply) => WRITES_TO_SCREENS.includes(apply.toolName));
  checks.push({ name: 'clone_project_screens для main (с копиями экранов) применён раньше правок раскладки',
    ok: firstDetach >= 0 && firstEdit > firstDetach && done.filter(detach).some((apply) => argsOf(apply)?.copyScreens !== false),
    detail: done.map((apply) => apply.toolName).join(' → ') });

  const edits = done.filter((apply) => WRITES_TO_SCREENS.includes(apply.toolName));
  const touched = edits.flatMap((apply) => targetScreens(argsOf(apply)));
  checks.push({ name: 'изменение раскладки применено только к экрану main и не к общим',
    ok: edits.length > 0 && touched.length > 0 && touched.every((id) => Object.hasOwn(now.contents, id) && !sharedScreens.has(Number(id))),
    detail: `экраны ${touched.join(',')}; общие ${[...sharedScreens].join(',')}` });
  const editIds = new Set(now.operations.map(entry => Number(entry.screens[OPERATION_KEY.EDIT])));
  const patch = argsOf(edits[0]);
  const canonical = id => String(id).replace(/^customfield_/, '');
  checks.push({ name: 'ровно один edit_screen_layout объединяет перенос f3 после Summary и снятие f2 на EDIT',
    ok: edits.length === 1 && edits[0].toolName === 'edit_screen_layout' && editIds.has(Number(patch?.screenId))
      && patch?.moves?.length === 1 && canonical(patch.moves[0].fieldId) === canonical(f3)
      && patch.moves[0].place === 'AFTER' && patch.moves[0].anchor === 'summary'
      && patch?.remove?.length === 1 && canonical(patch.remove[0]) === canonical(f2) && !patch?.add?.length,
    detail: JSON.stringify(patch) });
  const layoutPreviews = trace.filter(entry => WRITES_TO_SCREENS.includes(entry.tool) && entry.arguments?.dryRun === true);
  checks.push({ name: 'после отделения экрана одно совмещённое превью раскладки, без второго плана на том же экране',
    ok: layoutPreviews.length === 1 && layoutPreviews[0].tool === 'edit_screen_layout'
      && Number(layoutPreviews[0].arguments.screenId) === Number(patch?.screenId),
    detail: layoutPreviews.map(entry => `${entry.tool}:${entry.arguments?.screenId}`).join(', ') });
  checks.push({ name: `записи по проектам: ничего для ${other}, остальные только для ${main}`,
    ok: done.every((apply) => !argsOf(apply)?.project || argsOf(apply).project === main),
    detail: done.map((apply) => `${apply.toolName}:${argsOf(apply)?.project ?? '-'}`).join(', ') });
  checks.push({ name: 'set_field_required не применялся',
    ok: !done.some((apply) => apply.toolName === 'set_field_required'), detail: `${done.length} apply` });
  return checks;
}
