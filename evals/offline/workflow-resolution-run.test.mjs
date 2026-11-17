import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,rm} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {generateWorkflowResolutionExtraScenario} from './workflow-resolution-extra.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

for (const variant of ['missing','unresolved']) test(`CLI offline Resolution dialogue: ${variant}`, async t => {
  const scenario = generateWorkflowResolutionExtraScenario(variant);
  let requests = 0, inputChars = 0;
  const transportErrors = [];
  const resultFor = (body,name) => {
    const call = body.messages.flatMap(message => message.tool_calls ?? []).findLast(call => call.function.name === name);
    return JSON.parse(body.messages.find(message => message.role === 'tool' && message.tool_call_id === call.id).content);
  };
  const server = http.createServer(async (request,response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model,'offline-resolution-assistant');
      assert.ok(Array.isArray(body.tools), 'No synthetic-user or judge model');
      inputChars += text.length;
      requests++;
      const call = (name,args) => ({role:'assistant',content:null,tool_calls:[{id:`call-${requests}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]});
      const say = content => ({role:'assistant',content});
      let message;
      if (requests === 1) message = call('get_resolutions',{});
      else if (variant === 'unresolved') {
        assert.equal(requests,2);
        message = say('Unresolved (Нерешено) означает отсутствие значения Resolution. Для входа в DONE обязателен SET реального значения: нельзя установить Unresolved или создать его. Такой workflow создать нельзя.');
      } else {
        const resource = scenario.operations[0], workflow = scenario.operations[1];
        if (requests === 2) message = call('create_resolution',{dryRun:true,...resource.match});
        if (requests === 3) message = say('Preview: Resolution Cancelled, описание Work cancelled. Значение глобально для всех проектов Jira, не только workflow. Если workflow не создастся, глобальная Resolution останется. Применить создание Resolution?');
        if (requests === 4) message = call('start_operation',{toolName:'create_resolution',arguments:{dryRun:false,planId:resultFor(body,'create_resolution').planId}});
        if (requests === 5) message = call('get_operation',{operationId:resultFor(body,'start_operation').operationId});
        if (requests === 6) {
          assert.equal(resultFor(body,'get_operation').status,'COMPLETED');
          message = call('get_resolutions',{});
        }
        if (requests === 7) {
          const created = resultFor(body,'get_operation').result.resolution;
          assert.ok(resultFor(body,'get_resolutions').some(value => value.id === created.id));
          const spec = structuredClone(workflow.match);
          spec.transitions[0].resolution.value = created.id;
          message = call('configure_workflow', { dryRun: true, create: spec });
        }
        if (requests === 8) {
          const preview = resultFor(body,'configure_workflow');
          assert.equal(preview.transitions[1].resolution.action,'CLEAR');
          message = say('Preview workflow: Cancel устанавливает Resolution Cancelled; Reopen автоматически очистит Resolution до Unresolved. Применить workflow?');
        }
        if (requests === 9) message = call('start_operation',{toolName:'configure_workflow',arguments:{dryRun:false,planId:resultFor(body,'configure_workflow').planId}});
        if (requests === 10) message = call('get_operation',{operationId:resultFor(body,'start_operation').operationId});
        if (requests === 11) message = say('Workflow создан. Создана глобальная Resolution Cancelled; Reopen очищает Resolution.');
        assert.ok(message,'Unexpected extra request');
      }
      response.setHeader('Content-Type','application/json');
      response.end(JSON.stringify({choices:[{message}],usage:{prompt_tokens:0,completion_tokens:0,total_tokens:0}}));
    } catch (error) {
      transportErrors.push(error.message);
      response.writeHead(400).end(JSON.stringify({error:'Offline stub rejected request'}));
    }
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  let directory;
  try {
    const env = {...process.env,EVAL_API_KEY:'offline-key',EVAL_ASSISTANT_MODEL:'offline-resolution-assistant',
      EVAL_USER_MODEL:'forbidden-user-model',EVAL_JUDGE_MODEL:'forbidden-judge-model',
      EVAL_BASE_URL:`http://127.0.0.1:${server.address().port}`,EVAL_TOOL_BACKEND:'simulated',
      EVAL_TOKEN_LIMIT_MODE:'soft',EVAL_MAX_TOKENS_PER_RUN:'300000'};
    const {stdout} = await exec(process.execPath,['evals/offline/run.mjs',scenario.scenario],{cwd:ROOT,env,timeout:15000,maxBuffer:2*1024*1024});
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact,stdout);
    directory = path.resolve(ROOT,artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory,'verdict.json'),'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory,'tool-trace.json'),'utf8'));
    assert.deepEqual(transportErrors,[]);
    assert.equal(verdict.passed,true,JSON.stringify(verdict));
    assert.equal(requests,variant === 'missing' ? 11 : 2);
    if (variant === 'missing') {
      assert.deepEqual(trace.filter(call => call.tool === 'start_operation').map(call => call.arguments.toolName),['create_resolution','configure_workflow']);
      const creation = trace.find(call => call.tool === 'get_operation' && call.result.result?.resolution);
      const workflow = trace.find(call => call.tool === 'configure_workflow');
      assert.ok(trace.indexOf(creation) < trace.indexOf(workflow));
      assert.equal(workflow.arguments.create.transitions[0].resolution.value,creation.result.result.resolution.id);
    } else assert.deepEqual(trace.map(call => call.tool),['get_resolutions']);
    t.diagnostic(JSON.stringify({scenario:scenario.scenario,requests,inputChars,tools:26,liveCalls:0}));
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory,{recursive:true,force:true});
  }
});
