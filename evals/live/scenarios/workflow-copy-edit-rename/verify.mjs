import { isDeepStrictEqual as equal } from 'node:util';
import { applies } from '../../checks.mjs';

const ordered = (transitions) => [...transitions].sort((a, b) => a.actionId - b.actionId);

export default async function verify({ names, prepared, trace, tool, rest }) {
  const checks = [];
  const check = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  const original = await tool('get_workflow', { workflow: names['NAME:src'], version: 'ACTIVE' });
  check('исходный workflow не изменён: revision, шаги, переходы и Resolution',
    original.revision === prepared.revision && equal(original.steps, prepared.source.steps)
      && equal(ordered(original.transitions), ordered(prepared.source.transitions)),
    `revision ${prepared.revision} → ${original.revision}`);
  check('исходный workflow остался неактивным, без draft и потребителей',
    original.active === false && original.hasDraft === false
      && original.consumers.schemeCount === 0 && original.consumers.projectCount === 0,
    JSON.stringify({ active: original.active, hasDraft: original.hasDraft, consumers: original.consumers }));

  const workflows = await rest('GET', '/rest/api/2/workflow');
  const oldEntries = workflows.filter((w) => w.name === names['NAME:copy']);
  const finalEntries = workflows.filter((w) => w.name === names['NAME:final']);
  check('старое имя копии исчезло, итоговое имя существует ровно один раз',
    oldEntries.length === 0 && finalEntries.length === 1,
    `${names['NAME:copy']}: ${oldEntries.length}; ${names['NAME:final']}: ${finalEntries.length}`);
  const final = finalEntries.length === 1
    ? await tool('get_workflow', { workflow: names['NAME:final'], version: 'ACTIVE' }) : null;
  check('итоговый workflow неактивен, без draft и назначений схемам/проектам',
    final?.active === false && final.hasDraft === false
      && final.consumers.schemeCount === 0 && final.consumers.projectCount === 0,
    final ? JSON.stringify({ active: final.active, hasDraft: final.hasDraft, consumers: final.consumers }) : 'итогового workflow нет');

  const sourceShip = prepared.source.transitions.find((t) => t.name === 'Ship');
  const releases = final?.transitions.filter((t) => t.name === 'Release') ?? [];
  const release = releases[0];
  check('Ship переименован в Release с прежними id, концами и Resolution SET',
    sourceShip?.resolution.action === 'SET' && releases.length === 1
      && !final.transitions.some((t) => t.name === 'Ship')
      && equal(release, { ...sourceShip, name: 'Release' }),
    JSON.stringify({ before: sourceShip, after: releases }));

  const stepId = (status) => prepared.source.steps.find((s) => s.statusId === status.id)?.stepId;
  const backs = final?.transitions.filter((t) => t.name === 'Back to build') ?? [];
  const back = backs[0];
  check('Back to build — локальный QA → Building с явным Resolution CLEAR',
    backs.length === 1 && back.kind === 'LOCAL'
      && equal(back.sourceStepIds, [stepId(prepared.statuses.qa)])
      && equal(back.targetStepIds, [stepId(prepared.statuses.building)])
      && back.resolution?.action === 'CLEAR' && back.screenId == null
      && !prepared.source.transitions.some((t) => t.actionId === back.actionId),
    JSON.stringify(back ?? null));
  const expected = prepared.source.transitions.map((t) => t.name === 'Ship' ? { ...t, name: 'Release' } : t);
  check('все остальные переходы, правила и шаги сохранены без добавочных правок',
    final && equal(final.steps, prepared.source.steps) && final.description === prepared.source.description
      && backs.length === 1 && final.transitions.length === expected.length + 1
      && equal(ordered(final.transitions.filter((t) => t.actionId !== back.actionId)), ordered(expected)),
    `переходов: ${final?.transitions.length ?? 0}, ожидается ${expected.length + 1}`);

  const copies = applies(trace, 'copy_workflow');
  const edits = applies(trace, 'configure_workflow').filter((e) => e.preview?.arguments.existing);
  const renames = applies(trace, 'rename_workflow');
  check('ровно один apply copy_workflow, один configure_workflow, один rename_workflow; порядок copy → configure → rename',
    copies.length === 1 && edits.length === 1 && renames.length === 1
      && copies[0].start.seq < edits[0].start.seq && edits[0].start.seq < renames[0].start.seq,
    `copy #${copies.map((a) => a.start.seq)}; edit #${edits.map((a) => a.start.seq)}; rename #${renames.map((a) => a.start.seq)}`);
  check('копирование, правки и переименование адресованы только запрошенным workflow',
    copies.length === 1 && copies[0].preview?.arguments.workflow === names['NAME:src']
      && copies[0].preview?.arguments.name === names['NAME:copy']
      && edits.length === 1 && edits.every((e) => e.preview?.arguments.existing?.workflow === names['NAME:copy']
        && e.preview?.arguments.existing?.version === 'ACTIVE' && e.preview?.arguments.existing?.changes?.length === 2
        && !e.preview?.arguments.newScreens?.length && !e.preview?.arguments.transitionRules?.length)
      && renames.length === 1 && renames[0].preview?.arguments.workflow === names['NAME:copy']
      && renames[0].preview?.arguments.newName === names['NAME:final'],
    'проверены аргументы применённых preview');
  const extra = applies(trace).filter((a) => !['copy_workflow', 'configure_workflow', 'rename_workflow'].includes(a.toolName));
  check('publish_workflow_draft, assign_workflow и прочие лишние записи не применялись',
    extra.length === 0, extra.map((a) => `${a.toolName} #${a.start.seq}`).join('; ') || 'лишних apply нет');
  return checks;
}
