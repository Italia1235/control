// Preserve a real subprocess and its argv/cwd/env while replacing only the external executable.
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { fileURLToPath } from 'node:url';
const spawn = childProcess.spawn;
childProcess.spawn = (executable, args, options) => {
  if (executable !== process.env.OMP_EXECUTABLE) throw new Error('Unexpected executable at offline boundary');
  const env = { ...options.env };
  delete env.NODE_OPTIONS;
  return spawn(process.execPath, [fileURLToPath(new URL('./live-runner-fake-omp.mjs', import.meta.url)), ...args],
    { ...options, env });
};
syncBuiltinESMExports();
