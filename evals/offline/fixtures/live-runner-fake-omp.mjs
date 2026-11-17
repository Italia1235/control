// Executed as a real child process at the live runner -> omp boundary; no model or Jira is called.
import { appendFile, readFile, readdir } from 'node:fs/promises';
import path from 'node:path';

const args = process.argv.slice(2);
const directory = process.env.PI_CODING_AGENT_DIR;
const user = args.includes('--no-session');
const files = await readdir(directory);
const requestPath = args.find(arg => arg.startsWith('@'))?.slice(1);
const request = requestPath ? JSON.parse(await readFile(requestPath, 'utf8')) : null;
const config = files.includes('config.yml') ? await readFile(path.join(directory, 'config.yml'), 'utf8') : null;
const mcp = files.includes('mcp.json') ? JSON.parse(await readFile(path.join(directory, 'mcp.json'), 'utf8')) : null;
await appendFile(process.env.RUNNER_BOUNDARY_LOG, JSON.stringify({ role: user ? 'user' : 'assistant', args,
  cwd: process.cwd(), directory, files, request, config, mcp }) + '\n');
if (!user && process.env.RUNNER_BOUNDARY_EXPOSE_PLAN === '1') {
  console.log(JSON.stringify({ type: 'tool_execution_start', toolCallId: 'offline-preview', args: { dryRun: true } }));
  console.log(JSON.stringify({ type: 'tool_execution_end', toolCallId: 'offline-preview', toolName: 'mcp__jira_control_configure_workflow',
    result: { content: [{ type: 'text', text: JSON.stringify({ planId: 'offline-plan-sentinel' }) }] } }));
}
const setup = args.some(arg => arg.includes('authorizing setup for LOCAL-013'));
const text = user ? JSON.stringify(setup ? { decision: process.env.RUNNER_SETUP_DECISION || 'REJECT', message: 'Own simulated-user reply to the shown preview.' }
  : { message: 'Продолжай.', signal: 'CONTINUE' })
  : process.env.RUNNER_BOUNDARY_EXPOSE_PLAN === '1' ? 'Видимый ответ offline-plan-sentinel.' : 'Видимый ответ ассистента.';
console.log(JSON.stringify({ type: 'message_end', message: { role: 'assistant', provider: 'offline', model: 'fixture',
  content: [{ type: 'text', text }] } }));
