import { applies } from '../../checks.mjs';

const DRAFT_STATUS = 'In Progress';

export default async function verify({ names, trace, tool, probe }) {
  const [a, b, workflow] = [names['KEY:a'], names['KEY:b'], names['NAME:wf']];
  const checks = [];

  const own = await tool('get_project_workflows', { project: a });
  const shared = own.issueTypes.filter((type) => type.workflow === workflow).map((type) => type.issueType.name);
  checks.push({ name: `${a} больше не на «${workflow}»`, ok: own.issueTypes.length > 0 && shared.length === 0,
    detail: shared.length ? `ещё на оригинале: ${shared.join(', ')}` : own.issueTypes.map((type) => type.workflow).join(', ') });

  const other = await tool('get_project_workflows', { project: b });
  const moved = other.issueTypes.filter((type) => type.workflow !== workflow).map((type) => `${type.issueType.name}: ${type.workflow}`);
  checks.push({ name: `${b} остался на «${workflow}»`, ok: other.issueTypes.length > 0 && moved.length === 0, detail: moved.join('; ') || `${other.issueTypes.length} типа на оригинале` });

  // The draft is not part of the copy: the copies are made from the main version, which has no In Progress.
  const copies = [...new Set(own.issueTypes.map((type) => type.workflow))].filter((name) => name !== workflow);
  const withDraftStatus = [];
  for (const copy of copies) {
    const graph = await tool('get_workflow', { workflow: copy, version: 'ACTIVE' });
    if (JSON.stringify(graph).includes(DRAFT_STATUS)) withDraftStatus.push(copy);
  }
  checks.push({ name: `копии не содержат статус черновика ${DRAFT_STATUS}`, ok: copies.length > 0 && withDraftStatus.length === 0,
    detail: withDraftStatus.length ? `статус есть у: ${withDraftStatus.join(', ')}` : `проверено копий: ${copies.length}` });

  // The draft of the original stays unpublished and unchanged with its owner.
  const draft = await probe('publish_workflow_draft', { dryRun: true, workflow });
  checks.push({ name: 'черновик оригинала остался неопубликованным (превью публикации даёт WOULD_PUBLISH с добавленным статусом)',
    ok: draft.data?.status === 'WOULD_PUBLISH' && Boolean(draft.data?.changes?.addedStatuses?.includes(DRAFT_STATUS)),
    detail: `статус ${draft.data?.status ?? draft.text.slice(0, 120)}` });

  const publications = applies(trace, 'publish_workflow_draft');
  checks.push({ name: 'черновик не публикуется', ok: publications.length === 0, detail: `публикаций: ${publications.length}` });

  const clones = applies(trace, 'clone_project_workflows');
  checks.push({ name: 'копирование применено один раз по превью, показанному пользователю',
    ok: clones.length === 1 && Boolean(clones[0].preview), detail: `применений: ${clones.length}` });
  return checks;
}
