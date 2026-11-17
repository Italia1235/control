import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { apiConfiguration } from './run.mjs';
import { LAB_PAID_RUN, getPaidRun, assertPaidConfiguration } from './paid-run-configuration.mjs';

// Invoke only after explicit approval of one paid run; --check never starts a model.
const args = process.argv.slice(2);
const usage = 'Usage: node evals/offline/run-paid.mjs [--scenario <id>] [--check] (default: customfields_lab_two_screens)';
try {
  let scenario = LAB_PAID_RUN.scenario;
  let scenarioSpecified = false;
  let checkOnly = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--check' && !checkOnly) {
      checkOnly = true;
    } else if (args[i] === '--scenario' && !scenarioSpecified &&
        args[i + 1] && !args[i + 1].startsWith('--')) {
      scenario = args[++i];
      scenarioSpecified = true;
    } else {
      throw new Error(usage);
    }
  }
  const approved = getPaidRun(scenario);
  delete process.env.EVAL_ASSISTANT_MODEL;
  process.env.EVAL_BACKEND = 'simulated';
  process.env.EVAL_OMP_MODEL = approved.model;
  process.env.EVAL_TOKEN_LIMIT_MODE = approved.mode;
  process.env.EVAL_MAX_TOKENS_PER_RUN = String(approved.limit);
  const effective = assertPaidConfiguration(apiConfiguration(), scenario);
  console.log(JSON.stringify({ scenario, backend: process.env.EVAL_BACKEND,
    ...effective, checkOnly }, null, 2));
  if (!checkOnly) {
    console.log('Soft ceiling: possible overrun by one request plus retries. One simulated dialogue; no automatic rerun.');
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
    const child = spawnSync(process.execPath,
      [path.join(root, 'evals/offline/run.mjs'), scenario, '--paid'],
      { cwd: root, env: process.env, stdio: 'inherit', shell: false });
    if (child.error) console.error(child.error.message);
    process.exitCode = child.status ?? 1;
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
