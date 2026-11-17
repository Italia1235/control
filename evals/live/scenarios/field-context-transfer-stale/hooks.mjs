import { applies, parseResult } from '../../checks.mjs';

/** Uses Jira's native admin action for the concurrent edit; records only plugin reads, never issue data. */
export default async function afterAssistant(context) {
  const { prepared, lifecycle, trace, turn, tool, adminPage, log } = context;
  if (!lifecycle.injection) {
    const preview = trace.find((entry) => entry.tool === 'update_field_contexts' && entry.turn === turn
      && entry.arguments?.dryRun === true && entry.arguments?.fieldId === prepared.fieldId
      && entry.arguments?.contexts?.length === 2 && parseResult(entry.result)?.planId);
    if (preview) {
      const before = await tool('get_field_contexts', { field: prepared.fieldId });
      const main = before.contexts.find((item) => item.contextId === prepared.main.contextId);
      if (!main || main.allProjects || !main.allIssueTypes) throw new Error('Неожиданная область «Основного» до вмешательства');
      const editor = await adminPage(`/secure/admin/ManageConfigurationScheme!default.jspa?fieldId=${prepared.fieldId}&fieldConfigSchemeId=${main.contextId}`);
      const token = editor.text.match(/name="atlassian-token"\s+content="([^"]+)"/)?.[1];
      if (!token) throw new Error('Нет XSRF-токена административного редактора контекста');
      const parameters = [
        ['fieldId', prepared.fieldId], ['fieldConfigSchemeId', String(main.contextId)],
        ['fieldConfigIds', String(prepared.main.configId)], ['name', main.name], ['description', main.description ?? ''],
        ['global', 'false'], ['basicMode', 'true'], ['issuetypes', '-1'], ['atl_token', token],
        ...main.projects.map((project) => ['projects', String(project.id)]),
        ['projects', prepared.projects.concurrent.id],
      ];
      const response = await adminPage('/secure/admin/ManageConfigurationScheme.jspa', parameters);
      const after = await tool('get_field_contexts', { field: prepared.fieldId });
      const changed = after.contexts.find((item) => item.contextId === main.contextId);
      const expected = [...main.projects.map((project) => project.key), prepared.projects.concurrent.key].sort();
      if (JSON.stringify(changed?.projects.map((project) => project.key).sort()) !== JSON.stringify(expected)
          || changed.configs[0]?.configId !== prepared.main.configId) {
        throw new Error(`Вмешательство через UI Jira не сохранило нужную область: HTTP ${response.status}`);
      }
      lifecycle.injection = { previewPlanId: parseResult(preview.result).planId, previewSeq: preview.seq,
        turn, before, after, action: '/secure/admin/ManageConfigurationScheme.jspa', status: response.status };
      log(`после preview в «Основной» добавлен ${prepared.projects.concurrent.key} через административный редактор Jira`);
    }
  }

  for (const operation of applies(trace, 'update_field_contexts')) {
    const initial = parseResult(operation.start.result);
    const terminal = initial?.operationId
      ? trace.filter((entry) => entry.tool === 'get_operation' && entry.arguments?.operationId === initial.operationId)
        .map((entry) => parseResult(entry.result)).at(-1) ?? initial
      : initial;
    const result = terminal?.result;
    if (result?.errorCode === 'PREVIEW_STALE' && !lifecycle.stale) {
      lifecycle.stale = { result, contexts: await tool('get_field_contexts', { field: prepared.fieldId }), turn };
      log('PREVIEW_STALE: зафиксировано состояние после отказа без переноса');
    }
    if (result?.status !== 'UPDATED' || result.field?.id !== prepared.fieldId) continue;
    const main = result.contexts?.find((item) => item.contextId === prepared.main.contextId);
    const movingInMain = main?.actual?.projects?.some((project) => project.key === prepared.projects.moving.key);
    const role = movingInMain ? 'reverse' : 'forward';
    if (!lifecycle[role]) {
      lifecycle[role] = { result, contexts: await tool('get_field_contexts', { field: prepared.fieldId }),
        planId: operation.planId, turn };
      log(`${role}: зафиксировано состояние после применения`);
    }
  }
}
