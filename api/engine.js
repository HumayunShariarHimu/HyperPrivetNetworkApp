const engineUrl = () => (process.env.ENGINE_URL || '').replace(/\/$/, '');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function send(res, status, body, headers = {}) { res.status(status).setHeader('Cache-Control', 'no-store').setHeader('Access-Control-Allow-Origin', '*'); for (const [k, v] of Object.entries(headers)) res.setHeader(k, v); return res.send(body); }
function requestId(req) { return req.headers['x-request-id'] || `hpn-${Date.now()}-${Math.random().toString(16).slice(2)}`; }
async function call(path, options = {}, req) {
  if (!engineUrl()) throw Object.assign(new Error('ENGINE_URL is not configured on Vercel'), { code: 'CONFIG_ERROR', status: 503 });
  const timeoutMs = options.timeoutMs || (options.method === 'POST' && path === '/session' ? 90000 : 30000);
  const attempts = options.retryable ? 2 : 1;
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const r = await fetch(`${engineUrl()}${path}`, { ...options, timeoutMs: undefined, signal: controller.signal, headers: { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req), ...(options.headers || {}) } });
      const type = r.headers.get('content-type') || '';
      const data = type.includes('json') ? await r.json() : Buffer.from(await r.arrayBuffer());
      if (options.retryable && attempt === 0 && [502, 503, 504].includes(r.status)) { await sleep(250); continue; }
      return { status: r.status, type, data };
    } catch (error) {
      lastError = error;
      if (attempt + 1 < attempts) { await sleep(250); continue; }
    } finally { clearTimeout(timer); }
  }
  const timedOut = lastError?.name === 'AbortError';
  throw Object.assign(new Error(timedOut ? `Render engine did not respond within ${timeoutMs / 1000}s` : `Render engine unavailable: ${lastError?.message || 'upstream connection failed'}`), { code: timedOut ? 'UPSTREAM_TIMEOUT' : 'UPSTREAM_UNAVAILABLE', status: timedOut ? 504 : 503 });
}
export const config = { maxDuration: 120 };
export default async function handler(req, res) {
  if (req.method === 'OPTIONS') return send(res, 204, '');
  const id = req.query.id;
  try {
    if (req.method === 'GET' && req.query.asset === 'screenshot' && id) { const out = await call(`/session/${encodeURIComponent(id)}/screenshot`, { retryable: true, timeoutMs: 25000 }, req); return send(res, out.status, out.data, { 'Content-Type': out.type || 'image/jpeg', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'GET' && id) { const out = await call(`/session/${encodeURIComponent(id)}`, { retryable: true }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'GET' && req.query.pool === '1') { const out = await call('/pool', { retryable: true }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'GET' && req.query.catalog === '1') { const out = await call('/catalog', { retryable: true }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'GET') { const out = await call('/sessions', { retryable: true }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'POST' && req.query.action === 'close' && id) { const out = await call(`/session/${encodeURIComponent(id)}`, { method: 'DELETE', timeoutMs: 10000 }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'POST' && req.query.action === 'navigate' && id) { const out = await call(`/session/${encodeURIComponent(id)}/navigate`, { method: 'POST', body: JSON.stringify(req.body || {}), timeoutMs: 30000 }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'POST' && req.query.action === 'rotate' && id) { const out = await call(`/session/${encodeURIComponent(id)}/rotate`, { method: 'POST', timeoutMs: 90000 }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'POST') { const out = await call('/session', { method: 'POST', body: JSON.stringify(req.body || {}), timeoutMs: 90000 }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    if (req.method === 'DELETE' && id) { const out = await call(`/session/${encodeURIComponent(id)}`, { method: 'DELETE', timeoutMs: 30000 }, req); return send(res, out.status, JSON.stringify(out.data), { 'Content-Type': 'application/json', 'X-Request-ID': requestId(req) }); }
    return send(res, 405, JSON.stringify({ ok: false, error: 'Method not allowed' }), { 'Content-Type': 'application/json' });
  } catch (e) {
    const status = e.status || 503;
    return send(res, status, JSON.stringify({ ok: false, code: e.code || 'BRIDGE_ERROR', error: e.message, retryable: [502, 503, 504].includes(status), requestId: requestId(req) }), { 'Content-Type': 'application/json', 'Retry-After': '5', 'X-Request-ID': requestId(req) });
  }
}
