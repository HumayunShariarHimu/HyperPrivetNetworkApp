import express from 'express';
import cors from 'cors';
import crypto from 'node:crypto';
import fetch from 'node-fetch';
import { HttpProxyAgent } from 'http-proxy-agent';
import { HttpsProxyAgent } from 'https-proxy-agent';
import { SocksProxyAgent } from 'socks-proxy-agent';
import { chromium } from 'playwright';

const app = express();
app.use(cors());
app.use(express.json({ limit: '1mb' }));
const port = Number(process.env.PORT || 10000);
const maxSessions = Number(process.env.MAX_SESSIONS || 1);
const sessionTtlMs = Number(process.env.SESSION_TTL_SEC || 30) * 1000;
const publicDiscovery = process.env.AUTO_DISCOVER_PUBLIC !== 'false';
const sessions = new Map();
let sharedBrowser = null;
const history = [];
const startedAt = Date.now();
process.on('uncaughtException', (error) => {
  if (/ECONNRESET|ECONNREFUSED|ETIMEDOUT|Client network socket disconnected/i.test(error?.code || error?.message || '')) {
    console.warn(`Ignored transient public-proxy socket error: ${error.code || error.message}`);
    return;
  }
  console.error('Fatal engine error', error);
  process.exitCode = 1;
});
process.on('unhandledRejection', (error) => {
  if (/ECONNRESET|ECONNREFUSED|ETIMEDOUT|Client network socket disconnected/i.test(error?.code || error?.message || '')) {
    console.warn(`Ignored transient public-proxy rejection: ${error.code || error.message}`);
    return;
  }
  console.error('Unhandled engine rejection', error);
});
const publicPool = { candidates: [], working: [], checkedAt: 0, building: false, stats: { sources: 0, candidates: 0, tested: 0, working: 0 }, catalog: { http: [], https: [], socks4: [], socks5: [] }, sources: [] };
const SOURCES = [
  'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt',
  'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/https.txt',
  'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt',
  'https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt',
  'https://raw.githubusercontent.com/iplocate/free-proxy-list/main/protocols/http.txt',
  'https://raw.githubusercontent.com/iplocate/free-proxy-list/main/protocols/https.txt',
  'https://raw.githubusercontent.com/iplocate/free-proxy-list/main/protocols/socks4.txt',
  'https://raw.githubusercontent.com/iplocate/free-proxy-list/main/protocols/socks5.txt',
  'https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/http.txt',
  'https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/https.txt',
  'https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/socks4.txt',
  'https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/socks5.txt'
];
function parseProxy(value, fallbackProtocol = 'http') { const raw = String(value || '').trim(); if (!raw || raw.startsWith('#')) return null; let text = raw; if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = `${fallbackProtocol}://${text}`; try { const u = new URL(text); if (!['http:','https:','socks4:','socks5:'].includes(u.protocol) || !u.hostname || !u.port) return null; return u.toString(); } catch { return null; } }
function redactProxy(proxy) { try { const u = new URL(proxy); return `${u.protocol}//${u.hostname}:${u.port}`; } catch { return 'configured'; } }
function protocolOf(proxy) { try { return new URL(proxy).protocol.replace(':', ''); } catch { return 'http'; } }
function configuredPool() { return [...new Set([process.env.ROTATING_PROXY_URL, ...(process.env.PROXY_LIST || '').split(/[,\n]/)].map(parseProxy).filter(Boolean))]; }
function proxyKey(proxy) { return String(proxy || '').trim().toLowerCase(); }
function agentFor(proxy, target) { const a = /^socks/i.test(proxy) ? new SocksProxyAgent(proxy) : target.startsWith('https:') ? new HttpsProxyAgent(proxy, { rejectUnauthorized: false }) : new HttpProxyAgent(proxy); a.on('error', () => {}); return a; }
async function sourceList(url) { const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), 6500); try { const r = await fetch(url, { signal: controller.signal, headers: { 'User-Agent': 'HPN-Proxy-Discovery/1.0' } }); if (!r.ok) return []; const text = await r.text(); if (url.endsWith('.json') || url.includes('format=json')) { const rows = JSON.parse(text); return rows.map(x => parseProxy(`${x.protocol || 'http'}://${x.ip}:${x.port}`)).filter(Boolean); } const fallback = /socks5/i.test(url) ? 'socks5' : /socks4/i.test(url) ? 'socks4' : /https/i.test(url) ? 'https' : 'http'; return [...new Set(text.split(/\r?\n/).map(x => parseProxy(x, fallback)).filter(Boolean))]; } catch { return []; } finally { clearTimeout(timer); } }
async function proxyExit(proxy, timeoutMs = 3000) { const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeoutMs); const started = Date.now(); try { for (const target of ['https://api.ipify.org?format=json', 'https://ipinfo.io/json']) { try { const r = await fetch(target, { agent: agentFor(proxy, target), signal: controller.signal, headers: { 'User-Agent': 'HPN-Proxy-Validator/1.0', Accept: 'application/json' } }); if (!r.ok) continue; const data = await r.json(); const ip = data?.ip || data?.origin; if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(String(ip || ''))) return { ip, country: data.country || '', city: data.city || '', org: data.org || '', latency: Date.now() - started }; } catch {} } return null; } finally { clearTimeout(timer); } }
async function browserProxyExit(proxy, timeoutMs = 7000) { const context = await (await getBrowser()).newContext({ ignoreHTTPSErrors: true, proxy: { server: proxy } }); const page = await context.newPage(); page.setDefaultTimeout(timeoutMs); try { await page.goto('https://api.ipify.org?format=json', { waitUntil: 'commit', timeout: timeoutMs }); const data = JSON.parse(await page.locator('body').innerText({ timeout: 4000 })); return /^\d{1,3}(?:\.\d{1,3}){3}$/.test(String(data?.ip || '')) ? { ip: data.ip, latency: 0 } : null; } catch { return null; } finally { await context.close().catch(() => {}); } }
async function discoverPublic(force = false) { if (!publicDiscovery) return []; if (!force && publicPool.working.length && Date.now() - publicPool.checkedAt < 10 * 60 * 1000) return publicPool.working; if (publicPool.building) return publicPool.working; publicPool.building = true; try { const lists = await Promise.all(SOURCES.map(async url => ({ url, proxies: await sourceList(url) }))); const unique = [...new Set(lists.flatMap(x => x.proxies))]; publicPool.candidates = unique.sort(() => Math.random() - .5); publicPool.catalog = { http: [], https: [], socks4: [], socks5: [] }; for (const proxy of unique) { const protocol = protocolOf(proxy); if (publicPool.catalog[protocol]) publicPool.catalog[protocol].push(proxy); } publicPool.sources = lists.map(x => ({ url: x.url, count: x.proxies.length, fetchedAt: Date.now() })); publicPool.stats.sources = lists.filter(x => x.proxies.length).length; publicPool.stats.candidates = unique.length; const sample = publicPool.candidates.slice(0, Number(process.env.PUBLIC_PROXY_SAMPLE || 80)); const working = []; let cursor = 0; const worker = async () => { while (cursor < sample.length && working.length < Number(process.env.PUBLIC_PROXY_TARGET || 8)) { const proxy = sample[cursor++]; publicPool.stats.tested++; const exit = await proxyExit(proxy); if (!exit) continue; const browserExit = await browserProxyExit(proxy); if (browserExit && browserExit.ip === exit.ip && !working.some(x => x.exit.ip === browserExit.ip)) working.push({ proxy, exit: { ...exit, latency: browserExit.latency || exit.latency }, checkedAt: Date.now(), failures: 0 }); } }; await Promise.all(Array.from({ length: 8 }, worker)); publicPool.working = working; publicPool.stats.working = working.length; publicPool.checkedAt = Date.now(); return working; } finally { publicPool.building = false; } }
async function chooseProxy(session, forceDifferent = false) {
  const configured = configuredPool();
  let choices = configured.map(proxy => ({ proxy, kind: 'configured' }));
  if (!choices.length) {
    if (!publicPool.working.length) return null;
    choices = publicPool.working.map(x => ({ proxy: x.proxy, kind: 'public', exit: x.exit }));
  }
  if (!choices.length) return null;
  const knownIps = new Set((session.history || []).map(item => item.ip).filter(Boolean));
  if (session.exit?.ip) knownIps.add(session.exit.ip);
  const currentKey = proxyKey(session.proxy);
  const available = forceDifferent
    ? choices.filter(x => proxyKey(x.proxy) !== currentKey && (!x.exit?.ip || !knownIps.has(x.exit.ip)))
    : choices;
  if (!available.length) return null;
  return available[Math.floor(Math.random() * available.length)];
}
async function exitInfo(context) { const probe = await context.newPage().catch(() => null); if (!probe) return null; try { for (const target of ['https://api.ipify.org?format=json', 'https://ipinfo.io/json']) { try { await probe.goto(target, { waitUntil: 'commit', timeout: 5000 }); const d = JSON.parse(await probe.locator('body').innerText({ timeout: 3000 })); if (d?.ip) return { ip: d.ip, country: d.country || '', city: d.city || '', org: d.org || '' }; } catch {} } return null; } finally { await probe?.close().catch(() => {}); } }
async function getBrowser() { if (!sharedBrowser || !sharedBrowser.isConnected()) sharedBrowser = await chromium.launch({ headless: true, args: ['--disable-dev-shm-usage', '--no-sandbox', '--ignore-certificate-errors'] }); return sharedBrowser; }
async function closeBrowser(s) { if (s.context) await s.context.close().catch(() => {}); s.browser = sharedBrowser; s.context = null; s.page = null; }
function quarantine(proxy) { if (!proxy) return; publicPool.working = publicPool.working.filter(item => item.proxy !== proxy); publicPool.stats.working = publicPool.working.length; }
async function safeNavigate(s, url) { try { await s.page.goto(url, { waitUntil: 'commit', timeout: 8000 }); } catch (error) { if (!/ERR_ABORTED|Target page, context or browser has been closed/i.test(error.message || '')) throw error; } await s.page.waitForLoadState('domcontentloaded', { timeout: 8000 }).catch(() => {}); s.currentUrl = url; s.updatedAt = Date.now(); }
async function launch(s, forceDifferent = false) {
  let lastError;
  const poolSize = configuredPool().length || publicPool.working.length;
  const attempts = Math.max(2, Math.min(8, poolSize || 2));
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const selected = await chooseProxy(s, forceDifferent || attempt > 0);
    if (!selected) {
      lastError = new Error(forceDifferent ? 'No distinct verified proxy exit is available' : 'No verified proxy exit is available; direct mode is disabled');
      break;
    }
    const nextProxy = selected?.proxy || null;
    const nextKind = selected?.kind || 'direct';
    let nextContext;
    try {
      const browser = await getBrowser();
      nextContext = await browser.newContext({ viewport: { width: 1365, height: 820 }, ignoreHTTPSErrors: true, ...(nextProxy ? { proxy: { server: nextProxy } } : {}) });
      const nextPage = await nextContext.newPage();
      nextPage.setDefaultTimeout(15000);
      const nextExit = await exitInfo(nextContext);
      if (!nextExit) throw new Error('Proxy exit verification failed');
      if (forceDifferent && nextExit?.ip && (s.exit?.ip === nextExit.ip || s.history.some(item => item.ip === nextExit.ip))) throw new Error(`Proxy returned a previously used exit IP ${nextExit.ip}`);
      if (s.currentUrl) {
        const oldPage = s.page;
        s.page = nextPage;
        try { await safeNavigate(s, s.currentUrl); } finally { s.page = oldPage; }
      }
      const oldContext = s.context;
      s.browser = browser; s.context = nextContext; s.page = nextPage; s.proxy = nextProxy; s.proxyKind = nextKind; s.exit = nextExit; s.status = 'connected'; s.error = null; s.updatedAt = Date.now();
      await oldContext?.close().catch(() => {});
      if (s.exit) { const entry = { at: new Date().toISOString(), ip: s.exit.ip, country: s.exit.country, proxy: s.proxy ? redactProxy(s.proxy) : 'direct', kind: s.proxyKind }; s.history.push(entry); history.push({ sessionId: s.id, ...entry }); }
      return;
    } catch (error) {
      lastError = error;
      await nextContext?.close().catch(() => {});
      if (nextKind === 'public') quarantine(nextProxy);
    }
  }
  throw lastError || new Error('No distinct verified exit is available');
}
async function rotate(s, reason = 'timer') {
  if (s.rotating) return publicSession(s);
  s.rotating = true; s.status = 'rotating'; s.updatedAt = Date.now();
  const oldState = { proxy: s.proxy, proxyKind: s.proxyKind, exit: s.exit, currentUrl: s.currentUrl };
  try {
    try {
      await launch(s, true);
    } catch (error) {
      const canRefresh = publicDiscovery && !configuredPool().length
        && /No distinct verified proxy exit|previously used exit IP|Proxy exit verification failed/i.test(error.message || '')
        && Date.now() - (s.lastPoolRefresh || 0) > 15000;
      if (!canRefresh) throw error;
      s.lastPoolRefresh = Date.now();
      await discoverPublic(true);
      await launch(s, true);
    }
    s.lastRotation = { at: new Date().toISOString(), reason };
    return publicSession(s);
  } catch (error) {
    // Keep the previous session usable when a rotation pool temporarily fails.
    s.proxy = oldState.proxy; s.proxyKind = oldState.proxyKind; s.exit = oldState.exit;
    s.status = oldState.exit ? 'connected' : 'error';
    s.error = error.message;
    throw error;
  } finally {
    s.rotating = false;
    if (s.status === 'rotating') s.status = 'connected';
  }
}
function timerFor(s) { if (s.timer) clearInterval(s.timer); s.timer = setInterval(() => rotate(s, 'interval').catch(e => { s.status = 'error'; s.error = e.message; s.rotating = false; }), s.intervalSec * 1000); }
function publicSession(s) { return { id: s.id, status: s.status, proxy: s.proxy ? redactProxy(s.proxy) : 'direct', proxyKind: s.proxyKind, intervalSec: s.intervalSec, currentUrl: s.currentUrl, exit: s.exit, history: s.history.slice(-20), createdAt: s.createdAt, updatedAt: s.updatedAt }; }
async function reapSessions() { for (const s of sessions.values()) { if (!s.rotating && Date.now() - s.updatedAt > sessionTtlMs) { clearInterval(s.timer); await closeBrowser(s); sessions.delete(s.id); } } }
app.get('/health', (_req, res) => res.json({ ok: true, service: 'hpn-browser-engine', uptimeSec: Math.round((Date.now() - startedAt) / 1000), sessions: sessions.size, maxSessions, proxyConfigured: configuredPool().length > 0, publicDiscovery, publicWorking: publicPool.working.length, discovery: publicPool.stats }));
app.get('/pool', (_req, res) => res.json({ ok: true, publicDiscovery, stats: publicPool.stats, working: publicPool.working.map(x => ({ proxy: redactProxy(x.proxy), exit: x.exit, checkedAt: x.checkedAt })) }));
app.get('/catalog', (_req, res) => res.json({ ok: true, refreshedAt: publicPool.checkedAt || null, stats: publicPool.stats, sources: publicPool.sources, counts: Object.fromEntries(Object.entries(publicPool.catalog).map(([key, value]) => [key, value.length])), lists: Object.fromEntries(Object.entries(publicPool.catalog).map(([key, value]) => [key, value.slice(0, 500).map(redactProxy)])) }));
app.post('/pool/refresh', async (_req, res) => {
  if (!publicDiscovery) return res.status(409).json({ ok: false, error: 'Public discovery is disabled' });
  if (publicPool.building) return res.status(202).json({ ok: true, building: true, count: publicPool.working.length, stats: publicPool.stats });
  try {
    const working = await discoverPublic(true);
    res.json({ ok: true, count: working.length, stats: publicPool.stats });
  } catch (e) {
    res.status(502).json({ ok: false, error: e.message });
  }
});
app.get('/sessions', (_req, res) => res.json({ ok: true, sessions: [...sessions.values()].map(publicSession) }));
app.post('/session', async (req, res) => { if (sessions.size >= maxSessions) { await reapSessions(); if (sessions.size >= maxSessions) return res.status(409).json({ ok: false, code: 'SINGLE_SESSION_ACTIVE', error: 'One HPN session is already active. Stop it before starting another.' }); } const id = crypto.randomUUID(); const s = { id, status: 'starting', intervalSec: Math.max(5, Math.min(3600, Number(req.body?.intervalSec || 15))), currentUrl: String(req.body?.url || 'https://example.com'), proxy: null, proxyKind: 'direct', proxyRequired: false, exit: null, history: [], createdAt: Date.now(), updatedAt: Date.now(), browser: null, context: null, page: null, timer: null, rotating: false }; sessions.set(id, s); try { if (!configuredPool().length && publicDiscovery && !publicPool.working.length) await Promise.race([discoverPublic(true), new Promise(resolve => setTimeout(resolve, 25000))]); s.proxyRequired = configuredPool().length > 0 || publicPool.working.length > 0; await launch(s, false); timerFor(s); res.status(201).json({ ok: true, session: publicSession(s) }); } catch (e) { sessions.delete(id); await closeBrowser(s); res.status(502).json({ ok: false, error: `Browser start failed: ${e.message}` }); } });
app.get('/session/:id', (req, res) => { const s = sessions.get(req.params.id); if (!s) return res.status(404).json({ ok: false, error: 'Session not found' }); s.updatedAt = Date.now(); return res.json({ ok: true, session: publicSession(s) }); });
app.post('/session/:id/navigate', async (req, res) => { const s = sessions.get(req.params.id); if (!s) return res.status(404).json({ ok: false, error: 'Session not found' }); const url = String(req.body?.url || ''); if (!/^https?:\/\//i.test(url)) return res.status(400).json({ ok: false, error: 'Only http(s) URLs are allowed' }); try { await safeNavigate(s, url); res.json({ ok: true, session: publicSession(s) }); } catch (e) { if (s.proxyKind === 'public') { quarantine(s.proxy); s.currentUrl = url; try { await launch(s, true); return res.json({ ok: true, recovered: true, session: publicSession(s) }); } catch {} } res.status(502).json({ ok: false, error: e.message, session: publicSession(s) }); } });
app.get('/session/:id/screenshot', async (req, res) => { const s = sessions.get(req.params.id); if (!s?.page) return res.status(404).json({ ok: false, error: 'Session not found' }); try { const image = await s.page.screenshot({ type: 'jpeg', quality: 72 }); res.set('Content-Type', 'image/jpeg').send(image); } catch (e) { res.status(502).json({ ok: false, error: e.message }); } });
app.post('/session/:id/rotate', async (req, res) => { const s = sessions.get(req.params.id); if (!s) return res.status(404).json({ ok: false, error: 'Session not found' }); if (s.rotating) return res.status(202).json({ ok: true, pending: true, session: publicSession(s) }); try { res.json({ ok: true, session: await rotate(s, 'manual') }); } catch (e) { res.status(502).json({ ok: false, error: e.message, session: publicSession(s) }); } });
app.delete('/session/:id', async (req, res) => { const s = sessions.get(req.params.id); if (!s) return res.status(404).json({ ok: false, error: 'Session not found' }); clearInterval(s.timer); await closeBrowser(s); sessions.delete(s.id); res.json({ ok: true }); });
const shutdown = async () => { for (const s of sessions.values()) { clearInterval(s.timer); await closeBrowser(s); } await sharedBrowser?.close().catch(() => {}); process.exit(0); }; process.on('SIGTERM', shutdown); process.on('SIGINT', shutdown);
const server = app.listen(port, '0.0.0.0', () => { console.log(`HPN browser engine listening on ${port}`); setInterval(() => reapSessions().catch(() => {}), 60 * 1000); if (publicDiscovery) { setTimeout(() => discoverPublic(true).catch(() => {}), 30000); setInterval(() => discoverPublic(true).catch(() => {}), 10 * 60 * 1000); } });
server.keepAliveTimeout = 120000;
server.headersTimeout = 125000;
