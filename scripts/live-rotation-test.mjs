const base = process.env.HPN_URL || 'http://127.0.0.1:10000';
const getJson = async (path, options = {}) => {
  const response = await fetch(`${base}${path}`, { ...options, headers: { 'content-type': 'application/json', ...(options.headers || {}) } });
  const body = await response.text();
  let data;
  try { data = JSON.parse(body); } catch { throw new Error(`${path} returned ${response.status}: ${body}`); }
  if (!response.ok) throw new Error(`${path} returned ${response.status}: ${JSON.stringify(data)}`);
  return data;
};
const pool = await getJson('/pool/refresh', { method: 'POST' });
console.log('POOL', JSON.stringify(pool));
const started = await getJson('/session', { method: 'POST', body: JSON.stringify({ intervalSec: 5, url: 'https://example.com' }) });
const id = started.session.id;
const ips = [started.session.exit?.ip].filter(Boolean);
console.log('START', JSON.stringify(started.session));
try {
  for (let i = 1; i <= 4; i += 1) {
    const result = await getJson(`/session/${id}/rotate`, { method: 'POST' });
    const session = result.session;
    const ip = session.exit?.ip;
    console.log(`ROTATE_${i}`, JSON.stringify({ status: session.status, ip, proxy: session.proxy, history: session.history?.length }));
    if (!ip || ips.includes(ip)) throw new Error(`Duplicate or missing exit on rotation ${i}: ${ip}`);
    ips.push(ip);
  }
  console.log('PASS', JSON.stringify({ rotations: ips.length - 1, uniqueIps: ips }));
} finally {
  await getJson(`/session/${id}`, { method: 'DELETE' }).catch((error) => console.error('CLEANUP', error.message));
}
