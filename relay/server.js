import http from 'node:http';
import net from 'node:net';
import crypto from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';

const port = Number(process.env.PORT || 10000);
const token = process.env.RELAY_TOKEN || 'hpn-2026-relay';
const maxConnections = Number(process.env.MAX_CONNECTIONS || 64);
const maxSeconds = Number(process.env.MAX_TUNNEL_SECONDS || 3600);
let active = 0;

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ ok: true, service: 'hpn-relay', active, maxConnections, uptimeSec: Math.round(process.uptime()) }));
    return;
  }
  res.writeHead(404, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ ok: false, error: 'Not found' }));
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024, perMessageDeflate: false });
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', 'http://localhost');
  if (url.pathname !== '/tunnel') {
    socket.destroy();
    return;
  }
  if (active >= maxConnections) {
    socket.write('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req));
});

function validToken(value) {
  const a = Buffer.from(String(value || ''));
  const b = Buffer.from(token);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

wss.on('connection', ws => {
  active += 1;
  let upstream = null;
  let authorized = false;
  let closed = false;
  const timer = setTimeout(() => close('tunnel_expired'), maxSeconds * 1000);

  const close = reason => {
    if (closed) return;
    closed = true;
    clearTimeout(timer);
    upstream?.destroy();
    if (ws.readyState === WebSocket.OPEN) ws.close(1000, reason);
    active = Math.max(0, active - 1);
  };

  ws.on('message', (data, isBinary) => {
    if (!authorized) {
      if (isBinary || Buffer.byteLength(data) > 2048) return close('invalid_handshake');
      let hello;
      try { hello = JSON.parse(data.toString()); } catch { return close('invalid_handshake'); }
      const host = String(hello.host || '').trim();
      const targetPort = Number(hello.port);
      if (!validToken(hello.token) || !host || !Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) return close('unauthorized');
      authorized = true;
      upstream = net.createConnection({ host, port: targetPort });
      upstream.setTimeout(10000);
      upstream.once('connect', () => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'connected' }));
      });
      upstream.on('data', chunk => { if (ws.readyState === WebSocket.OPEN) ws.send(chunk, { binary: true }); });
      upstream.on('error', () => close('upstream_error'));
      upstream.on('timeout', () => close('upstream_timeout'));
      upstream.on('close', () => close('upstream_closed'));
      return;
    }
    if (!upstream || !Buffer.isBuffer(data)) return;
    upstream.write(data);
  });
  ws.on('close', () => close('client_closed'));
  ws.on('error', () => close('client_error'));
});

server.listen(port, '0.0.0.0', () => console.log(`HPN relay listening on ${port}`));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
