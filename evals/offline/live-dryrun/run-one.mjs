import { spawn } from 'node:child_process';
import { startFakeJira } from './fake-jira.mjs';
const [scenario, mode = 'good'] = process.argv.slice(2);
const jira = await startFakeJira();
const child = spawn(process.execPath, ['--import', new URL('./fake-omp.mjs', import.meta.url).href, 'evals/live/run.mjs', scenario, '--max-turns', '8'],
  { env: { ...process.env, JIRA_BASE_URL: jira.url, JIRA_TOKEN: 'x', DRYRUN_SCENARIO: scenario, DRYRUN_MODE: mode }, stdio: 'inherit' });
child.on('close', async (code) => { await jira.close(); console.log('exit', code); });
