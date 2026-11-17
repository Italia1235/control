// Opt-in test preload. Keeps the real CLI child and existing HTTP-fixture handlers,
// but transports requests over process IPC; no server listens and no socket connects.
import http from 'node:http';
import net from 'node:net';
import childProcess from 'node:child_process';
import { EventEmitter } from 'node:events';
import { syncBuiltinESMExports } from 'node:module';
import { Readable } from 'node:stream';

const servers = new Map();
let nextPort = 20000;
const preload = import.meta.url;
net.Socket.prototype.connect = function () { throw new Error('Network sockets are forbidden in offline dialogue tests'); };

async function dispatch(url, options = {}) {
  const address = new URL(url);
  const server = servers.get(Number(address.port));
  if (address.hostname !== '127.0.0.1' || !server)
    throw new Error('fetch failed: network request is forbidden; no in-memory fixture for this address');
  return new Promise((resolve, reject) => {
    const headers = {};
    let status = 200, content = '';
    let settled = false;
    const finish = (error, result) => {
      if (settled) return;
      settled = true; server.requests.delete(cancel);
      if (error) reject(error); else resolve(result);
    };
    const cancel = () => finish(new Error('In-memory fixture closed the request'));
    server.requests.add(cancel);
    const response = {
      writeHead(code, values = {}) { status = code; Object.assign(headers, values); return this; },
      setHeader(name, value) { headers[name] = value; return this; },
      write(chunk) { content += chunk; return true; },
      end(chunk = '') { content += chunk; finish(null, { status, headers, body: content }); },
    };
    const request = Object.assign(Readable.from(options.body ? [Buffer.from(options.body)] : []),
      { url: address.pathname + address.search, method: options.method ?? 'GET', headers: options.headers ?? {} });
    Promise.resolve().then(() => server.handler(request, response)).catch(error => finish(error));
    options.signal?.addEventListener('abort', () => finish(new DOMException('Aborted', 'AbortError')), { once: true });
  });
}

if (process.env.EVAL_OFFLINE_IPC_CHILD === '1') {
  const pending = new Map();
  let sequence = 0;
  process.on('message', message => {
    if (message?.type !== 'offline-response') return;
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    if (pending.size === 0) process.channel?.unref();
    if (message.error) waiter.reject(new Error(message.error));
    else waiter.resolve(new Response(message.result.body, { status: message.result.status, headers: message.result.headers }));
  });
  process.channel?.unref();
  globalThis.fetch = (url, options = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const abort = () => {
      pending.delete(id);
      if (pending.size === 0) process.channel?.unref();
      reject(new DOMException('Aborted', 'AbortError'));
    };
    if (options.signal?.aborted) return abort();
    options.signal?.addEventListener('abort', abort, { once: true });
    pending.set(id, { resolve, reject });
    process.channel?.ref();
    process.send({ type: 'offline-request', id, url: String(url), options: {
      method: options.method, body: options.body, headers: Object.fromEntries(new Headers(options.headers)) } });
  });
} else {
  http.createServer = handler => {
    const server = new EventEmitter();
    server.handler = handler;
    server.requests = new Set();
    server.closeAllConnections = () => { for (const cancel of [...server.requests]) cancel(); };
    server.listen = (_port, _host, callback) => {
      server.port = ++nextPort; servers.set(server.port, server); queueMicrotask(callback); return server;
    };
    server.address = () => ({ address: '127.0.0.1', family: 'IPv4', port: server.port });
    server.close = callback => {
      server.closeAllConnections(); servers.delete(server.port);
      queueMicrotask(callback ?? (() => {})); return server;
    };
    return server;
  };
  globalThis.fetch = async (url, options) => {
    const result = await dispatch(String(url), options);
    return new Response(result.body, { status: result.status, headers: result.headers });
  };
  const originalExec = childProcess.execFile;
  childProcess.execFile = function (file, args, options, callback) {
    if (file !== process.execPath) return originalExec(file, args, options, callback);
    const child = childProcess.spawn(file, args, { cwd: options.cwd, windowsHide: true,
      env: { ...options.env, EVAL_OFFLINE_IPC_CHILD: '1',
        NODE_OPTIONS: `${options.env?.NODE_OPTIONS ?? ''} --import=${preload}` },
      stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
    let stdout = '', stderr = '', overflow = false;
    const timer = options.timeout ? setTimeout(() => child.kill(), options.timeout) : null;
    child.stdout.on('data', chunk => { stdout += chunk; if (stdout.length > (options.maxBuffer ?? 1024 * 1024)) { overflow = true; child.kill(); } });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('message', async message => {
      if (message?.type !== 'offline-request') return;
      let reply;
      try { reply = { result: await dispatch(message.url, message.options) }; }
      catch (failure) { reply = { error: failure.message }; }
      if (child.connected) child.send({ type: 'offline-response', id: message.id, ...reply });
    });
    child.on('error', error => { clearTimeout(timer); callback(error, stdout, stderr); });
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      const error = code === 0 && !overflow ? null : Object.assign(new Error(`Offline CLI exited ${code ?? signal}`),
        { code: overflow ? 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER' : code, stdout, stderr, signal });
      callback(error, stdout, stderr);
    });
    return child;
  };
  childProcess.execFile[Symbol.for('nodejs.util.promisify.custom')] = (file, args, options) =>
    new Promise((resolve, reject) => childProcess.execFile(file, args, options, (error, stdout, stderr) =>
      error ? reject(error) : resolve({ stdout, stderr })));
  syncBuiltinESMExports();
}
