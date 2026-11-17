import { parseResult } from '../../checks.mjs';
import { canonical, flagMap } from '../incident-screen-required/setup.mjs';

const ROLES = ['system', 'serial', 'stage', 'risk', 'klass', 'placeNew'];

/**
 * Once, after the assistant turn that previewed both the screen and the required flags and before any apply of the
 * screen: moves Assignee to the end of the screen's tab with Jira's own REST, like an administrator in the UI would.
 * The plugin is not involved; the assistant's screen preview no longer matches the screen. Evidence goes to
 * lifecycle.injection: screen order before/after, HTTP status, required flags of the six fields at that moment.
 */
export default async function afterAssistant({ jira, prepared, lifecycle, trace, turn, tool, log }) {
  if (lifecycle.injection) return;
  const planned = (name) => trace.find((entry) => entry.tool === name && entry.turn === turn && entry.arguments?.dryRun === true
    && parseResult(entry.result)?.planId);
  const screenPreview = planned('edit_screen_layout');
  const requiredPreview = planned('set_field_required');
  const screenApplied = trace.some((entry) => entry.tool === 'start_operation' && entry.arguments?.toolName === 'edit_screen_layout');
  if (!screenPreview || !requiredPreview || screenApplied) return;

  const order = async () => {
    const read = await tool('get_screen_contents', { screenId: prepared.screenId });
    if (read.tabs.length !== 1) throw new Error(`Ожидалась одна вкладка экрана ${prepared.screenId}: ${read.tabs.length}`);
    return { tabId: read.tabs[0].id, fields: read.tabs[0].fields.map((field) => field.fieldId ?? field.id) };
  };
  const before = await order();
  if (before.fields.at(-1) === 'assignee' || !before.fields.includes('assignee')) {
    throw new Error(`Assignee нельзя передвинуть в конец: ${before.fields.join(', ')}`);
  }

  const path = `/rest/api/2/screens/${prepared.screenId}/tabs/${before.tabId}/fields/assignee/move`;
  const response = await fetch(jira.baseUrl + path, {
    method: 'POST',
    headers: { authorization: jira.authorization, 'X-Atlassian-Token': 'no-check', 'content-type': 'application/json' },
    body: JSON.stringify({ position: 'Last' }),
  });
  if (!response.ok) throw new Error(`POST ${path}: HTTP ${response.status} ${(await response.text()).slice(0, 300)}`);
  const after = await order();
  if (after.fields.at(-1) !== 'assignee') throw new Error(`Assignee не последний после переноса: ${after.fields.join(', ')}`);

  const fieldIds = {};
  for (const role of ROLES) fieldIds[role] = ['system', 'serial'].includes(role) ? prepared.ids[role]
    : (await tool('get_field_contexts', { field: prepared.fieldNames[role] })).field.id;
  const configs = await tool('get_field_configurations', { project: prepared.keys.target, fields: Object.values(fieldIds) });
  const bugConfig = configs.mappings.find((entry) => entry.issueTypeName === prepared.typeName)?.fieldConfiguration;
  const flags = flagMap(configs);
  const required = Object.fromEntries(ROLES.map((role) => [role, flags[`${canonical(fieldIds[role])}@${bugConfig?.id}`]?.required]));

  lifecycle.injection = {
    turn, screenPreviewSeq: screenPreview.seq, requiredPreviewSeq: requiredPreview.seq,
    screenId: prepared.screenId, tabId: before.tabId, action: `POST ${path} {"position":"Last"}`, status: response.status,
    orderBefore: before.fields, orderAfter: after.fields, fieldIds, required,
  };
  log(`после превью экрана и обязательности Assignee перенесён в конец вкладки ${before.tabId} (HTTP ${response.status})`);
}
