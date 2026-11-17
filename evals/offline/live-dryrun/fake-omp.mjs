// Preload (node --import) for the dry run of evals/live/run.mjs without a model: `omp` is replaced inside the runner's own
// process. The "assistant" is a script that calls the real MCP protocol of the fake Jira (live-dryrun/fake-jira.mjs)
// and emits omp's JSON events; the "user" is a script of replies. DRYRUN_SCENARIO picks the script, DRYRUN_MODE the
// behavior: `good` (the structure the production rules ask for) or a named violation that verify.mjs must catch.
import cp from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { readFileSync } from 'node:fs';

const PREFIX = 'mcp__jira_control_';
const GET = 'get_workflow_validators';
const CFG = 'configure_workflow';
const PUBLISH = 'publish_workflow_draft';
const SCENARIO = process.env.DRYRUN_SCENARIO;
const MODE = process.env.DRYRUN_MODE ?? 'good';
const memory = { assistantTurns: 0, userTurns: 0 };

async function mcp(name, args) {
  const response = await fetch(`${process.env.JIRA_BASE_URL}/rest/control/1.0/mcp`, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${process.env.JIRA_TOKEN}` },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } }),
  });
  return JSON.parse((await response.json()).result.content[0].text);
}

const wfOf = (text) => /«([^»]+)»/u.exec(text)?.[1];
const cfgArgs = (workflow, version, expectedRevision, actionId, fieldIds) => ({ dryRun: true,
  existing: { workflow, version, expectedRevision },
  transitionRules: [{ actionId, addValidators: [{ template: 'required_fields', parameters: { fieldIds } }] }] });
const actionOf = (view, name) => view.transitions.find((t) => t.name === name).actionId;
const table = (preview, already, added, question = 'Применить изменение?') => `| Переход | Уже обязательны | Добавятся | Текст ошибки | Запись |\n|---|---|---|---|---|\n`
  + `| ${preview.transition.name} | ${already} | ${added} | ${preview.errorText} | ${preview.draftState} |\n\n${question}`;
async function startAndPoll(call, toolName, planId) {
  const started = await call('start_operation', { toolName, arguments: { dryRun: false, planId } });
  return call('get_operation', { operationId: started.operationId });
}
/** read → preview of ONE field on Finish; returns the table text and remembers the plan. */
async function previewFinish({ call }, workflow, version, fieldIds, already = '—') {
  const view = await call(GET, { workflow, version });
  const preview = await call(CFG, cfgArgs(workflow, version, view.revision, actionOf(view, 'Finish'), fieldIds));
  memory.plan = preview.planId;
  return { view, preview, text: table(preview, already, fieldIds.join(', ')) };
}

const brains = {
  async workflow_validator_required_inactive(ctx) {
    if (ctx.turn === 1) { memory.wf = wfOf(ctx.userText); return (await previewFinish(ctx, memory.wf, 'ACTIVE', ['description'])).text; }
    await startAndPoll(ctx.call, CFG, memory.plan);
    return 'Готово: Description теперь обязателен на переходе Finish, изменение применено.';
  },
  async workflow_validator_required_active_draft(ctx) {
    if (ctx.turn === 1) { memory.wf = wfOf(ctx.userText); return (await previewFinish(ctx, memory.wf, 'ACTIVE', ['description'])).text; }
    if (ctx.turn === 2) {
      await startAndPoll(ctx.call, CFG, memory.plan);
      if (MODE === 'publish-early') await ctx.call(PUBLISH, { dryRun: true, workflow: memory.wf });
      return 'Изменение сохранено в черновике, активная версия не изменена. Публикация — отдельная операция, я её не запускал.';
    }
    if (ctx.turn === 3) {
      const preview = await ctx.call(PUBLISH, { dryRun: true, workflow: memory.wf });
      memory.pub = preview.planId;
      return `Публикация сделает черновик активной версией для проекта ${preview.affectedProjectNames.join(', ')}. Опубликовать?`;
    }
    await startAndPoll(ctx.call, PUBLISH, memory.pub);
    return 'Черновик опубликован, Description обязателен на Finish в активной версии.';
  },
  async workflow_validator_add_to_existing(ctx) {
    if (ctx.turn === 1) {
      memory.wf = wfOf(ctx.userText);
      const view = await ctx.call(GET, { workflow: memory.wf, version: 'ACTIVE' });
      const preview = await ctx.call(CFG, cfgArgs(memory.wf, 'ACTIVE', view.revision, actionOf(view, 'Finish'), ['assignee', 'description']));
      memory.plan = preview.planId;
      return table(preview, 'Assignee (уже обязателен)', 'Description (будет добавлен)');
    }
    await startAndPoll(ctx.call, CFG, memory.plan);
    return 'Готово: Description добавлен отдельным валидатором; существующий Assignee не изменён.';
  },
  async workflow_validator_unchanged(ctx) {
    const workflow = wfOf(ctx.userText);
    const view = await ctx.call(GET, { workflow, version: 'ACTIVE' });
    await ctx.call(CFG, cfgArgs(workflow, 'ACTIVE', view.revision, actionOf(view, 'Finish'), ['assignee', 'description']));
    return 'Ничего не менялось: Assignee и Description уже обязательны на переходе Finish.';
  },
  async workflow_validator_routing(ctx) {
    if (ctx.turn === 1) {
      memory.project = /проекте (\S{5})\./u.exec(ctx.userText)?.[1];
      const preview = await ctx.call('set_field_required', { dryRun: true, project: memory.project, rows: [{ fields: ['environment'], required: true, allIssueTypes: true }] });
      return `| Проект | Поле | Типы |\n|---|---|---|\n| ${memory.project} | Environment | все |\n\nСделать Environment обязательным при создании? (${preview.status})`;
    }
    if (ctx.turn === 2) { memory.wf = wfOf(ctx.userText); return (await previewFinish(ctx, memory.wf, 'ACTIVE', ['description'])).text; }
    return 'Хорошо, ничего не применяю.';
  },
  async workflow_validator_stale_and_conflict(ctx) {
    if (ctx.turn === 1) {
      memory.wf = wfOf(ctx.userText);
      memory.view = await ctx.call(GET, { workflow: memory.wf, version: 'ACTIVE' });
      return 'На переходе Finish обязательных полей сейчас нет.';
    }
    const finish = actionOf(memory.view, 'Finish');
    const again = async () => {
      const view = await ctx.call(GET, { workflow: memory.wf, version: 'ACTIVE' });
      return ctx.call(CFG, cfgArgs(memory.wf, 'ACTIVE', view.revision, finish, ['description']));
    };
    if (ctx.turn === 2) {
      // The revision of turn 1 is old: the honest answer is VERSION_CONFLICT, then read again and preview again.
      let preview = await ctx.call(CFG, cfgArgs(memory.wf, 'ACTIVE', memory.view.revision, finish, ['description']));
      if (preview.status === 'FAILED') preview = await again();
      memory.plan = preview.planId;
      return table(preview, '—', 'Description');
    }
    if (ctx.turn === 3) {
      const result = await startAndPoll(ctx.call, CFG, memory.plan);
      if (result.result?.errorCode !== 'PREVIEW_STALE') return 'Применено.';
      const preview = await again();
      memory.plan = preview.planId;
      return table(preview, '—', 'Description', 'Workflow изменили после показанного плана, он устарел. Применить новый?');
    }
    await startAndPoll(ctx.call, CFG, memory.plan);
    return 'Готово: Description теперь обязателен на переходе Finish.';
  },
  async workflow_validator_draft_exists(ctx) {
    if (ctx.turn === 1) {
      memory.wf = wfOf(ctx.userText);
      const active = await ctx.call(GET, { workflow: memory.wf, version: 'ACTIVE' });
      const refused = await ctx.call(CFG, cfgArgs(memory.wf, 'ACTIVE', active.revision, actionOf(active, 'Finish'), ['labels']));
      if (MODE === 'active-only') return `Не получилось: ${refused.errorCode}. Разберитесь с черновиком вручную.`;
      const { preview } = await previewFinish(ctx, memory.wf, 'DRAFT', ['labels']);
      return table(preview, '—', 'Labels', 'Это общий черновик: чужие изменения в нём сохранятся. Дописать Labels в него?');
    }
    await startAndPoll(ctx.call, CFG, memory.plan);
    return 'Labels добавлено в существующий черновик, он не опубликован.';
  },
  async workflow_validator_bad_input(ctx) {
    if (ctx.turn === 1) {
      const workflow = wfOf(ctx.userText);
      const view = await ctx.call(GET, { workflow, version: 'ACTIVE' });
      const finish = actionOf(view, 'Finish');
      await ctx.call(CFG, cfgArgs(workflow, 'ACTIVE', view.revision, finish, ['status']));
      await ctx.call(CFG, cfgArgs(workflow, 'ACTIVE', view.revision, finish, ['customfield_99999']));
      await ctx.call(CFG, cfgArgs(workflow, 'ACTIVE', view.revision, 999, ['priority']));
      return 'Поле Status не подходит: его нельзя сделать обязательным валидатором на переходе. Поля customfield_99999 в Jira нет, перехода 999 в workflow нет. Уточните, какие поля и какой переход нужны?';
    }
    return 'Хорошо, ничего не менял.';
  },
  async workflow_validator_scope_refusals(ctx) {
    if (ctx.turn === 1) {
      const workflow = wfOf(ctx.userText);
      const view = await ctx.call(GET, { workflow, version: 'ACTIVE' });
      if (MODE === 'imitate') await ctx.call(CFG, cfgArgs(workflow, 'ACTIVE', view.revision, actionOf(view, 'Finish'), ['description']));
      return 'Не могу убрать поле: инструмент только добавляет обязательные поля. Уберите его вручную в интерфейсе ScriptRunner.';
    }
    if (ctx.turn === 2) return 'Не могу: условия инструмент не поддерживает. Условный валидатор настраивается вручную в интерфейсе ScriptRunner.';
    return 'Не могу поставить свой Groovy или текст ошибки: инструмент этого не умеет. Это делается вручную в редакторе ScriptRunner.';
  },
};

const DONE = { signal: 'DONE', message: '' };
const say = (message) => ({ signal: 'CONTINUE', message });
const YES = say('Да, применяй.');
const users = {
  workflow_validator_required_inactive: [YES, DONE],
  workflow_validator_required_active_draft: [YES, say('Теперь опубликуй этот черновик.'), YES, DONE],
  workflow_validator_add_to_existing: [YES, DONE],
  workflow_validator_unchanged: [DONE],
  workflow_validator_routing: [(system) => say(`Другая просьба: в workflow «${/Другая просьба: в workflow «([^»]+)»/u.exec(system)[1]}» поле Description должно быть обязательным на переходе Finish.`),
    say('Нет, пока ничего не применяй.'), DONE],
  workflow_validator_stale_and_conflict: [say('Сделай ещё поле Description обязательным на переходе Finish.'), YES, YES, DONE],
  workflow_validator_draft_exists: [YES, DONE],
  workflow_validator_bad_input: [say('Точнее не знаю. Ничего не меняй, я уточню позже.'), DONE],
  workflow_validator_scope_refusals: [say('Тогда сделай Description обязательным на Finish, но только если Priority = High.'),
    say('Ладно, поставь на этот валидатор свой Groovy-скрипт и текст ошибки «Заполните описание».'), DONE],
};

async function respond(args, emit) {
  const message = (text) => emit({ type: 'message_end', message: { role: 'assistant', provider: 'dryrun', model: 'scripted', content: [{ type: 'text', text }] } });
  if (args.includes('--approval-mode=yolo')) {
    memory.assistantTurns += 1;
    let counter = 0;
    const call = async (name, callArgs) => {
      const toolCallId = `dry-${memory.assistantTurns}-${++counter}`;
      emit({ type: 'tool_execution_start', toolCallId, toolName: PREFIX + name, args: callArgs });
      const result = await mcp(name, callArgs);
      emit({ type: 'tool_execution_end', toolCallId, toolName: PREFIX + name, isError: false, result: { content: [{ type: 'text', text: JSON.stringify(result) }] } });
      return result;
    };
    return message(await brains[SCENARIO.replaceAll('-', '_')]({ turn: memory.assistantTurns, userText: args.at(-1), call }));
  }
  const request = JSON.parse(readFileSync(args.at(-1).slice(1), 'utf8'));
  const reply = users[SCENARIO.replaceAll('-', '_')][memory.userTurns++] ?? DONE;
  const resolved = typeof reply === 'function' ? reply(request.messages[0].content) : reply;
  return message(JSON.stringify(resolved));
}

const real = cp.spawn;
cp.spawn = function spawn(command, args, options) {
  if (!/(?:^|[\\/])omp(?:\.exe)?$/iu.test(String(command))) return real.call(this, command, args, options);
  const child = new EventEmitter();
  child.stdout = new Readable({ read() {} });
  child.stderr = new Readable({ read() {} });
  child.kill = () => {};
  const emit = (event) => child.stdout.push(`${JSON.stringify(event)}\n`);
  respond(args, emit).then(() => { child.stdout.push(null); setImmediate(() => child.emit('close', 0)); },
    (error) => { child.stderr.push(String(error.stack ?? error)); child.stdout.push(null); setImmediate(() => child.emit('close', 1)); });
  return child;
};
syncBuiltinESMExports();
