import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { startFakeJira } from './live-dryrun/fake-jira.mjs';

const exec = promisify(execFile);
const scenario = 'workflow-comment-l1-inactive';
const environment = () => {
  const env = { ...process.env, JIRA_TOKEN: 'offline-fixture-token' };
  delete env.LOCAL005_EVIDENCE_FILE;
  return env;
};

test('LOCAL-005 live CLI refuses simulated-user authorization before contacting Jira or models', async () => {
  await assert.rejects(exec(process.execPath, ['evals/live/run.mjs', scenario], {
    env: { ...environment(), JIRA_BASE_URL: 'http://127.0.0.1:1/jira' }, timeout: 10_000,
  }), error => error.code === 2 && /requires --human-user/u.test(error.stderr));
});

test('LOCAL-005 --check loads the existing live runner and scenario without setup, models or mutations', async () => {
  const jira = await startFakeJira();
  try {
    const { stdout } = await exec(process.execPath, ['evals/live/run.mjs', scenario, '--check'], {
      env: { ...environment(), JIRA_BASE_URL: jira.url }, timeout: 10_000,
    });
    assert.match(stdout, /workflow-comment-l1-inactive/u);
    assert.match(stdout, /при --check не выполняется/u);
    assert.equal(jira.log.some(entry => /start_operation|configure_workflow/u.test(JSON.stringify(entry))), false);
  } finally { await jira.close(); }
});

test('read-only setup fails closed when no approved LOCAL-005 fixture manifest is supplied', async () => {
  const jira = await startFakeJira();
  try {
    await assert.rejects(exec(process.execPath, ['evals/live/run.mjs', scenario, '--setup-only'], {
      env: { ...environment(), JIRA_BASE_URL: jira.url }, timeout: 10_000,
    }), error => error.code !== 0 && /BLOCKED|evidence|manifest|LOCAL005_EVIDENCE_FILE/iu.test(error.stderr));
    assert.equal(jira.log.some(entry => /start_operation/u.test(JSON.stringify(entry))), false);
  } finally { await jira.close(); }
});
