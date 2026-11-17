import { applies, parseResult } from '../../checks.mjs';

export default async function verify({ names, runId, trace, tool, rest }) {
  const workflow = names['NAME:wf'];
  const resolutionName = `Rejected by Client ${runId}`;
  const checks = [];

  const found = await tool('get_resolutions', { query: resolutionName });
  checks.push({ name: `резолюция «${resolutionName}» создана ровно одна`, ok: found.length === 1, detail: `найдено ${found.length}` });
  const newId = found[0]?.id;

  const resolutionApplies = applies(trace, 'create_resolution');
  checks.push({ name: 'создана одна резолюция, и только эта',
    ok: resolutionApplies.length === 1 && resolutionApplies[0].preview?.arguments?.name === resolutionName,
    detail: resolutionApplies.map((apply) => apply.preview?.arguments?.name).join(', ') || 'нет apply' });

  const readCatalog = trace.find((entry) => entry.tool === 'get_resolutions');
  const resolutionPreview = resolutionApplies[0]?.preview;
  checks.push({ name: 'каталог Resolution прочитан до превью создания', ok: Boolean(readCatalog && resolutionPreview && readCatalog.seq < resolutionPreview.seq),
    detail: `get_resolutions #${readCatalog?.seq}, превью #${resolutionPreview?.seq}` });

  const created = applies(trace, 'configure_workflow').filter((apply) => apply.preview?.arguments?.create).at(-1);
  checks.push({ name: 'резолюция и workflow подтверждены в разных ходах, резолюция раньше',
    ok: Boolean(resolutionApplies[0] && created && resolutionApplies[0].start.turn < created.start.turn),
    detail: `резолюция — ход ${resolutionApplies[0]?.start.turn}, workflow — ход ${created?.start.turn}` });
  checks.push({ name: 'применённое превью workflow построено после создания резолюции (с настоящим ID)',
    ok: Boolean(created?.preview && resolutionApplies[0] && created.preview.seq > resolutionApplies[0].start.seq),
    detail: `превью #${created?.preview?.seq}, создание резолюции #${resolutionApplies[0]?.start.seq}` });

  const transitions = created?.preview?.arguments?.create?.transitions ?? [];
  const valueOf = (name) => transitions.find((transition) => transition.name === name)?.resolution;
  const [done] = await tool('get_resolutions', { query: 'Done' });
  const [fixed] = await tool('get_resolutions', { query: 'Fixed' });
  const approve = valueOf('Approve');
  const allowed = [done?.id, fixed?.id].filter(Boolean);
  checks.push({ name: 'Approve ставит Done (или Fixed), не «Готово» и не новую', ok: approve?.action === 'SET' && allowed.includes(approve.value),
    detail: `Approve ${JSON.stringify(approve)}, допустимо ${allowed.join('/')}` });
  const reject = valueOf('Reject');
  checks.push({ name: 'Reject ставит новую резолюцию', ok: reject?.action === 'SET' && reject.value === newId, detail: `Reject ${JSON.stringify(reject)}, новая ${newId}` });

  const operation = trace.filter((entry) => entry.tool === 'get_operation').map((entry) => parseResult(entry.result)).find((result) => result?.operation === 'configure_workflow');
  checks.push({ name: 'workflow создан (операция COMPLETED)', ok: operation?.status === 'COMPLETED', detail: `${workflow}: ${operation?.status ?? 'операции нет в трассе'}` });
  return checks;
}
