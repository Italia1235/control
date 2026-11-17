export default async function setup({ names, tool, apply, log }) {
  const catalog = await tool('list_statuses', { limit: 100 });
  const statuses = catalog.statuses;
  const used = new Set();
  const choose = (preferred, category) => {
    const candidates = statuses.filter((s) => s.category === category && !used.has(s.id));
    const selected = candidates.find((s) => s.name === preferred)
      ?? candidates.sort((a, b) => a.name.localeCompare(b.name) || String(a.id).localeCompare(String(b.id)))[0];
    if (!selected) throw new Error(`Нет существующего статуса для ${preferred} (${category}); получено ${statuses.length}/${catalog.total}`);
    used.add(selected.id);
    return selected;
  };
  const start = choose('Open', 'TO_DO');
  const building = choose('Building', 'IN_PROGRESS');
  const qa = choose('QA', 'IN_PROGRESS');
  const done = choose('Done', 'DONE');
  const resolutions = await tool('get_resolutions', {});
  const resolution = resolutions.find((r) => /^fixed$/iu.test(r.name))
    ?? resolutions.find((r) => /^(done|completed|resolved|готово|выполнено|решено|исправлено)$/iu.test(r.name));
  if (!resolution) throw new Error('Нет подходящей существующей Resolution для Ship');
  const workflow = names['NAME:src'];
  await apply('configure_workflow', {
    create: {
      name: workflow,
      statuses: [start, building, qa, done].map(({ name, category }) => ({ name, category })),
      initialStatus: start.name,
      transitions: [
        { key: 'start-build', name: 'Start build', from: start.name, to: building.name },
        { key: 'send-to-qa', name: 'Send to QA', from: building.name, to: qa.name },
        { key: 'ship', name: 'Ship', from: qa.name, to: done.name, resolution: { action: 'SET', value: resolution.id } },
      ],
    },
  });
  const source = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  if (source.active || source.hasDraft || source.consumers.schemeCount !== 0 || source.consumers.projectCount !== 0) {
    throw new Error('Исходный workflow должен быть неактивным, без draft и потребителей');
  }
  const known = {
    start: start.name, Building: building.name, QA: qa.name, Done: done.name,
    resolution: { id: resolution.id, name: resolution.name },
  };
  log(`исходный «${workflow}»: ${start.name} → ${building.name} → ${qa.name} → ${done.name}; Ship SET ${resolution.name} (${resolution.id})`);
  return { workflow, known, statuses: { start, building, qa, done }, revision: source.revision, source };
}
