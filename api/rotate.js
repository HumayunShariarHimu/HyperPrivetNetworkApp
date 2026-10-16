// Legacy/serverless rotation endpoint. Always verifies the selected exit and rejects reused IPs.
import { pool, normalizeProxy, requestDirect, requestJsonThroughProxy, loadCandidates, json, jerr } from '../lib/proxy.js';

function output(data, proxy, latency, poolSize) {
  const [lat = '', lon = ''] = String(data.loc || ',').split(',');
  return { ok: true, ip: data.ip || '0.0.0.0', country: data.country || '', city: data.city || '', region: data.region || '', isp: data.org || '', org: data.org || '', asn: data.org || '', timezone: data.timezone || '', lat, lon, proxy, latency, poolSize, ts: Date.now() };
}

export default async function handler(req, res) {
  const started = Date.now();
  const mode = ['direct', 'free', 'auto'].includes(req.query?.mode) ? req.query.mode : 'auto';
  try {
    if (mode === 'direct') {
      const data = await requestDirect();
      return json(res, 200, output(data, 'direct', Date.now() - started, pool.working.length));
    }

    const endpoint = mode !== 'free' ? normalizeProxy(process.env.ROTATING_PROXY_URL) : null;
    const previousIp = String(req.query?.previousIp || '').trim();
    const excludedIps = new Set(String(req.query?.excludeIps || '').split(',').map((ip) => ip.trim()).filter(Boolean));
    const requestedProxy = normalizeProxy(req.query?.proxy || '');
    const previous = String(req.query?.previous || '').trim();
    const configured = mode !== 'free' ? (process.env.PROXY_LIST || '').split(/[,\n]/).map(normalizeProxy).filter(Boolean) : [];

    const accept = (result, proxy) => {
      const ip = String(result?.data?.ip || '');
      if (!result || !ip || ip === previousIp || excludedIps.has(ip)) return false;
      json(res, 200, output(result.data, proxy, Date.now() - started, pool.working.length));
      return true;
    };

    if (endpoint) {
      const result = await requestJsonThroughProxy(endpoint, 7000);
      if (accept(result, 'endpoint')) return;
    }

    if (requestedProxy && requestedProxy !== previous) {
      const result = await requestJsonThroughProxy(requestedProxy, 5000);
      if (accept(result, requestedProxy)) return;
    }

    let choices = [...configured, ...pool.working.map((item) => item.proxy).filter(Boolean)];
    choices = choices.filter((proxy, index, list) => list.indexOf(proxy) === index && proxy !== previous);

    if (choices.length < 4) {
      const candidates = await loadCandidates(false);
      const fresh = candidates.filter((candidate) => !pool.seen.has(candidate)).slice(0, 80);
      fresh.forEach((candidate) => pool.seen.add(candidate));
      const working = await Promise.all(fresh.map(async (hostPort) => {
        const proxy = normalizeProxy(hostPort);
        const result = await requestJsonThroughProxy(proxy, 3500);
        return result ? { proxy, ip: result.data.ip, latency: result.latency } : null;
      }));
      const knownIps = new Set(pool.working.map((item) => item.ip).filter(Boolean));
      for (const item of working.filter(Boolean)) {
        if (!knownIps.has(item.ip)) { knownIps.add(item.ip); pool.working.push(item); }
      }
      pool.working = pool.working.slice(-100);
      pool.stats.tested += fresh.length;
      pool.stats.working = pool.working.length;
      choices = [...configured, ...pool.working.map((item) => item.proxy).filter(Boolean)]
        .filter((proxy, index, list) => list.indexOf(proxy) === index && proxy !== previous);
    }

    const candidates = pool.working
      .filter((item) => item.ip && !excludedIps.has(item.ip) && item.ip !== previousIp && item.proxy !== previous)
      .map((item) => item.proxy);
    const available = [...new Set(candidates.length ? candidates : choices)];
    for (const proxy of available.sort(() => Math.random() - 0.5).slice(0, 8)) {
      const result = await requestJsonThroughProxy(proxy, 3500);
      const accepted = accept(result, proxy);
      if (accepted) return;
      pool.working = pool.working.filter((item) => item.proxy !== proxy);
    }

    return jerr(res, 503, 'No verified distinct proxy exit found.', { proxy: 'none', poolSize: pool.working.length, latency: Date.now() - started });
  } catch (error) {
    return jerr(res, 502, error?.message || 'proxy request failed', { proxy: 'auto', poolSize: pool.working.length, latency: Date.now() - started });
  }
}
