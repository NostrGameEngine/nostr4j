import assert from 'node:assert/strict';

const api = process.env.DEMO_ORIGIN || 'http://127.0.0.1:18081';
const frontend = process.env.DEMO_FRONTEND_ORIGIN || 'http://127.0.0.1:18080';
const request = (path, options = {}) => fetch(api + path, {
  signal: AbortSignal.timeout(15000), ...options,
});
const preflight = (origin, method = 'POST', headers = 'content-type') => request('/api/ping', {
  method: 'OPTIONS',
  headers: {
    Origin: origin,
    'Access-Control-Request-Method': method,
    'Access-Control-Request-Headers': headers,
  },
});

for (const origin of [frontend, 'https://nostrgameengine.github.io',
  'https://preview.ngengine.org', 'https://branch.project.workers.dev']) {
  const response = await preflight(origin);
  assert.equal(response.status, 204, origin);
  assert.equal(response.headers.get('access-control-allow-origin'), origin);
  assert.equal(response.headers.get('vary'), 'Origin');
  assert.equal(response.headers.get('access-control-allow-credentials'), null);
  const config = await request('/api/config', {headers: {Origin: origin}});
  assert.equal(config.status, 200);
  assert.equal(config.headers.get('access-control-allow-origin'), origin);
}
for (const origin of ['https://attacker.invalid',
  'https://nostrgameengine.github.io.attacker.invalid',
  'https://preview.ngengine.org.attacker.invalid', 'https://deep.preview.ngengine.org']) {
  const response = await preflight(origin);
  assert.equal(response.status, 403, origin);
  assert.equal(response.headers.get('access-control-allow-origin'), null);
  const config = await request('/api/config', {headers: {Origin: origin}});
  assert.equal(config.headers.get('access-control-allow-origin'), null);
}
assert.equal((await preflight(frontend, 'PUT')).status, 403);
assert.equal((await preflight(frontend, 'POST', 'authorization')).status, 403);
const invalid = await request('/api/ping', {
  method: 'POST', headers: {Origin: frontend, 'Content-Type': 'application/json'},
  body: '{"relays":["http://127.0.0.1"]}',
});
assert.equal(invalid.status, 400);
assert.equal(invalid.headers.get('access-control-allow-origin'), frontend);
assert.equal((await request('/')).status, 404, 'API-only mode must not serve the site');
console.log('PASS split-hosting CORS: exact/wildcard origins, preflight restrictions, POST policy and API-only mode.');
