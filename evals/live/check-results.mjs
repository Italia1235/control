import { writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Persist and print a verdict. Only explicitly advisory failures are nonblocking; legacy checks are contract. */
export async function reportChecks(directory, checks) {
  const classified = checks.map((item) => {
    if (item.class === undefined) return { ...item, class: 'contract' };
    if (item.class === 'contract' || item.class === 'advisory') return item;
    return { ...item, class: 'contract', ok: false,
      detail: `Unknown check class ${JSON.stringify(item.class)}${item.detail ? `; ${item.detail}` : ''}` };
  });
  const observations = classified.filter((item) => item.class === 'advisory' && !item.ok);
  const passed = classified.every((item) => item.class === 'advisory' || item.ok);
  await writeFile(path.join(directory, 'verdict.json'), `${JSON.stringify({ passed, checks: classified, observations }, null, 2)}\n`);
  console.log('\nПроверки:');
  for (const item of classified) {
    const status = item.ok ? 'PASS' : item.class === 'advisory' ? 'OBSERVATION' : 'FAIL';
    console.log(`  ${status}  ${item.name}${item.detail ? ` — ${item.detail}` : ''}`);
  }
  console.log(passed ? 'ИТОГ: PASS' : 'ИТОГ: FAIL');
  return passed;
}
