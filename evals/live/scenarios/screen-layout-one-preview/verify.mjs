import { applies } from '../../checks.mjs';
import { OPERATION_KEY, readLayout } from '../screen-own-layout/setup.mjs';

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const canonical = id => String(id).replace(/^customfield_/, '');

export default async function verify({ prepared, trace, tool }) {
  const { main, before, fieldIds: [f1, f2, f3, f4] } = prepared;
  const now = await readLayout(tool, main);
  const checks = [];
  checks.push({ name: 'собственная схема и назначения экранов не изменились',
    ok: same(now.scheme, before.scheme) && same(now.operations, before.operations), detail: JSON.stringify(now.operations) });
  const editIds = new Set(now.operations.map(entry => Number(entry.screens[OPERATION_KEY.EDIT])));
  for (const [index, entry] of now.operations.entries()) {
    const edit = now.contents[entry.screens[OPERATION_KEY.EDIT]];
    checks.push({ name: `${entry.issueType}: EDIT Summary,f3,Description,f1,f4`,
      ok: same(edit?.tabs.flatMap(tab => tab.fields), ['summary', f3, 'description', f1, f4]), detail: JSON.stringify(edit) });
    for (const op of ['CREATE', 'VIEW']) {
      const original = before.contents[before.operations[index].screens[OPERATION_KEY[op]]];
      const current = now.contents[entry.screens[OPERATION_KEY[op]]];
      checks.push({ name: `${entry.issueType}: ${op} не изменён`, ok: same(current, original), detail: JSON.stringify(current) });
    }
  }
  const done = applies(trace);
  const edits = done.filter(item => ['edit_screen_layout', 'add_field_to_screens', 'remove_field_from_screens'].includes(item.toolName));
  const previews = trace.filter(item => item.tool === 'edit_screen_layout' && item.arguments?.dryRun === true);
  const patch = edits[0]?.preview?.arguments;
  checks.push({ name: 'ровно одно превью и одно применение edit_screen_layout объединяют все три изменения',
    ok: previews.length === 1 && edits.length === 1 && edits[0].toolName === 'edit_screen_layout'
      && editIds.has(Number(patch?.screenId)) && patch?.moves?.length === 1
      && canonical(patch.moves[0].fieldId) === canonical(f3) && patch.moves[0].place === 'AFTER' && patch.moves[0].anchor === 'summary'
      && patch?.remove?.length === 1 && canonical(patch.remove[0]) === canonical(f2)
      && patch?.add?.length === 1 && canonical(patch.add[0].fieldId) === canonical(f4)
      && (patch.add[0].place === undefined || patch.add[0].place === 'LAST'),
    detail: JSON.stringify({ previews: previews.length, edits: edits.length, patch }) });
  checks.push({ name: 'других операций записи не было', ok: done.length === 1 && done[0].toolName === 'edit_screen_layout',
    detail: done.map(item => item.toolName).join(', ') });
  return checks;
}
