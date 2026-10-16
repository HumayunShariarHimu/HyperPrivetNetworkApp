import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const files = ['engine/server.js', 'api/engine.js', 'api/diag.js', 'api/info.js', 'api/pool.js', 'api/rotate.js'];
for (const file of files) execFileSync(process.execPath, ['--check', file], { stdio: 'inherit' });
const html = await readFile('index.html', 'utf8');
const required = ['HYPER PRIVATE NETWORK', 'Browse Through A <em>Rotating Exit</em>', '<footer>HPN - Humayun Shariar Himu</footer>', 'data-sec="5"', 'min="5"'];
for (const token of required) if (!html.includes(token)) throw new Error(`Missing required UI text: ${token}`);
for (const token of ['REAL BROWSER ROUTING / CONTROL PLANE', 'A remote Chromium session renders the page here', 'TRUST BOUNDARY', 'TRUTHFUL TELEMETRY']) if (html.includes(token)) throw new Error(`Removed UI text still present: ${token}`);
const kotlin = await readFile('android/app/src/main/java/com/hpn/app/MainActivity.kt', 'utf8');
if (kotlin.includes('WebView') || kotlin.includes('loadUrl')) throw new Error('Android activity still embeds a WebView');
for (const token of ['TIME SETUP', 'HPN ON or OFF', 'Humayun Shariar Himu', 'showTimeSettings']) if (!kotlin.includes(token)) throw new Error(`Minimal Android UI missing: ${token}`);
const engine = await readFile('engine/server.js', 'utf8');
if (!engine.includes('direct mode is disabled')) throw new Error('Web engine still permits implicit direct fallback');
const vpn = await readFile('android/app/src/main/java/com/hpn/app/HpnVpnService.kt', 'utf8');
for (const token of ['VpnService', 'TProxyStartService', 'addRoute("0.0.0.0", 0)', 'addRoute("::", 0)', 'ACTION_ROTATE']) if (!vpn.includes(token)) throw new Error(`Native VPN integration missing: ${token}`);
const repository = await readFile('android/app/src/main/java/com/hpn/app/ProxyRepository.kt', 'utf8');
for (const token of ['proxifly', 'proxyscrape', 'sockslist.us', 'spys.one', 'scrappey.com', 'freeproxy.world']) if (!repository.toLowerCase().includes(token)) throw new Error(`Live source missing: ${token}`);
if (!repository.includes('distinctBy') || !repository.includes('readTimeout') || !repository.includes('probeSocks5') || !repository.includes('SOCKS5 handshake')) throw new Error('Live source pool is not verified');
if (!repository.includes('example.com') || !repository.includes('CONNECT')) throw new Error('SOCKS5 relay verification missing');
if (!repository.includes('HTTP/1.1\\r\\nHost: example.com')) throw new Error('SOCKS5 HTTP request must use HTTP CRLF line endings');
if (!repository.includes('fun verify(host: String, port: Int)')) throw new Error('VPN rotation endpoint preflight overload missing');
if (!vpn.includes('ProxyRepository.verify(next.host, next.port)')) throw new Error('VPN rotation does not preflight its selected SOCKS5 endpoint');
const activity = await readFile('android/app/src/main/java/com/hpn/app/MainActivity.kt', 'utf8');
if (activity.includes('EditText') || activity.includes('proxy_list')) throw new Error('Manual proxy input still present');
if (!activity.includes('ON') || !activity.includes('OFF') || !activity.includes('⚙')) throw new Error('Minimal ON/OFF and settings controls missing');
if (!activity.includes('startAfterRefresh') || !activity.includes('requestVpnPermissionAndStart')) throw new Error('Start does not continue after live refresh');
if (!vpn.includes('rotateTunnel()') || !vpn.includes('New endpoint failed; restoring previous verified endpoint')) throw new Error('Native rotation is not fail-safe');
if (!vpn.includes('snapshot.endpoints.any')) throw new Error('Native rotation does not guard against an empty refresh');
console.log(`HPN verification passed: ${files.length} JS files, UI copy, live sources, proxy-only routing, and native TUN service checks.`);

// Dashboard metadata must never use decorative em-dash placeholders.
const serverSource = readFileSync(new URL('../engine/server.js', import.meta.url), 'utf8');
assert(!serverSource.includes("|| '—'"), 'engine must not emit em-dash metadata fallbacks');
assert(!serverSource.includes('|| "—"'), 'engine must not emit em-dash metadata fallbacks');
