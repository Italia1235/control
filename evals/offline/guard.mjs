// Exact local eval deployment; never derive this allowlist from the requested URL.
const ALLOWED = new Set(['http://localhost:8080', 'http://127.0.0.1:8080']);
export function assertAllowed(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('JIRA_BASE_URL is not an allowed local Jira URL'); }
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/' || !ALLOWED.has(url.origin)) {
    throw new Error('JIRA_BASE_URL rejected: only http://localhost:8080 or http://127.0.0.1:8080 is allowed');
  }
  return url.origin;
}

export function jiraClient({baseUrl, username, password, token, timeoutMs = 30000}) {
  const base = assertAllowed(baseUrl);
  const authorization = token ? `Bearer ${token}` : `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`;
  const cookies = new Map();
  return async function request(resource, {method = 'GET', body, headers = {}, raw = false, allowLocalRedirect = false} = {}) {
    assertAllowed(base);
    const target = new URL(resource, `${base}/`);
    if (target.origin !== base || !resource.startsWith('/') || resource.startsWith('//')) throw new Error('Refusing non-local Jira request');
    const response = await fetch(target, {
      method, redirect: 'manual', signal: AbortSignal.timeout(timeoutMs),
      headers: {authorization, accept:'application/json', cookie:[...cookies].map(([k,v]) => `${k}=${v}`).join('; '), ...(body != null ? {'content-type':'application/json'} : {}), ...headers},
      ...(body != null ? {body: typeof body === 'string' ? body : JSON.stringify(body)} : {}),
    });
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0];
      const index = pair.indexOf('=');
      if (index > 0) cookies.set(pair.slice(0,index), pair.slice(index+1));
    }
    if (response.status >= 300 && response.status < 400) {
      const location = new URL(response.headers.get('location'), target);
      if (!allowLocalRedirect || location.origin !== base) throw new Error('Jira redirect rejected');
      // Caller must explicitly read the local destination; credentials never follow redirects.
      return {redirect: location.pathname + location.search};
    }
    // Never print response bodies from authentication failures or reflected credentials.
    if (!response.ok) throw new Error(`Jira ${method} ${target.pathname}: HTTP ${response.status}`);
    if (response.status === 204 || response.status === 202) return null;
    return raw ? response.text() : response.json();
  };
}
