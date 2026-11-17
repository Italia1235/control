import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const reporter = new URL('../live/check-results.mjs', import.meta.url).href;
const cli = `import { reportChecks } from ${JSON.stringify(reporter)};
if (!await reportChecks(process.argv[1], JSON.parse(process.argv[2]))) process.exitCode = 3;`;
const contract = { name: 'Jira state unchanged', ok: false, detail: 'unexpected validator', class: 'contract' };
const observation = { name: 'Preview names the screen', ok: false, detail: 'screen name missing from reply', class: 'advisory' };

for (const { name, checks, passed, observations } of [
  { name: 'advisory-only failure passes and persists the complete observation', checks: [observation], passed: true, observations: [observation] },
  { name: 'contract failure fails', checks: [contract], passed: false, observations: [] },
  { name: 'mixed failures fail while preserving advisory observations', checks: [contract, observation], passed: false, observations: [observation] },
  { name: 'unclassified legacy failure remains contract', checks: [{ name: 'legacy', ok: false, detail: 'missing state' }], passed: false, observations: [] },
  { name: 'passing legacy and advisory checks pass without observations', checks: [{ name: 'legacy', ok: true }, { ...observation, ok: true }], passed: true, observations: [] },
  ...['typo', '', null].flatMap((classification) => [false, true].map((ok) => ({
    name: `unknown class ${JSON.stringify(classification)} fails closed even with ok=${ok}`,
    checks: [{ name: 'invalid class', ok, class: classification, detail: 'original detail' }], passed: false, observations: [],
  }))),
]) {
  test(`reportChecks: ${name}`, async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'live-results-'));
    try {
      const result = spawnSync(process.execPath, ['--input-type=module', '-e', cli, directory, JSON.stringify(checks)], { encoding: 'utf8' });
      assert.ifError(result.error);
      assert.equal(result.status, passed ? 0 : 3, result.stderr);
      const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
      assert.equal(verdict.passed, passed);
      assert.deepEqual(verdict.observations, observations);
      assert.match(result.stdout, new RegExp(`ИТОГ: ${passed ? 'PASS' : 'FAIL'}`));
      for (const check of verdict.checks) {
        assert.ok(['contract', 'advisory'].includes(check.class));
        const label = check.ok ? 'PASS' : check.class === 'advisory' ? 'OBSERVATION' : 'FAIL';
        assert.ok(result.stdout.includes(`${label}  ${check.name}`));
        if (check.detail) assert.ok(result.stdout.includes(check.detail));
      }
      if (checks.some((c) => c.class !== undefined && !['contract', 'advisory'].includes(c.class))) {
        assert.equal(verdict.checks[0].class, 'contract');
        assert.equal(verdict.checks[0].ok, false);
        assert.match(verdict.checks[0].detail, /Unknown check class.*original detail/u);
      }
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
}
