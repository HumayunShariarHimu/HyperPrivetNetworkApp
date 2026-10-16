# HPN — Hyper Privet Network

HPN is a mobile-responsive dark-neon dashboard for an **authorized remote Chromium session**. The browser engine runs on Render, while the dashboard and serverless bridge run on Vercel.

The `android/` directory contains a native Kotlin VPN service backed by the vendored MIT-licensed `hev-socks5-tunnel` TUN-to-SOCKS5 engine. The app has no manual proxy field: **UPDATE LIVE PROXIES** fetches fresh SOCKS candidates from the configured public sources into memory, and the service refreshes again before each timed rotation. It refuses to connect without a live SOCKS5 candidate and never fabricates IPs or black-holes phone traffic.

## What is real

The Render engine launches Chromium with Playwright and applies a proxy at browser-launch time. On every scheduled or manual rotation it closes the current browser and launches a new one with another configured proxy, then verifies the exit through `ipinfo.io`. The dashboard displays only telemetry returned by that browser session; it does not generate fake IP addresses.

When multiple verified exits are available, rotation excludes the current proxy and every verified exit IP already used in the session. HPN never falls back to direct mode: if no distinct verified exit is available, it reports the constrained pool rather than inventing an IP.

A normal website cannot change the public IP of the user's entire device or other apps. HPN changes the egress path of its **remote browser session**. Existing TCP/TLS connections may finish on the previous path; recreating the session is intentional.

## Repository layout

- `index.html`, `style.css`: Vercel-hosted HPN dashboard.
- `api/engine.js`: Vercel control-plane bridge to Render.
- `engine/server.js`: persistent Chromium session manager and rotation scheduler.
- `Dockerfile`: Playwright Chromium runtime for Render.
- `render.yaml`: Render service blueprint.
- `api/rotate.js`, `api/pool.js`, `api/info.js`, `api/diag.js`: legacy API-level proxy diagnostics retained for compatibility.

## Deployment

1. Create a Render Web Service from this repository using the included `Dockerfile`. Keep `MAX_SESSIONS=1` and add `PROXY_LIST` and/or `ROTATING_PROXY_URL` as **server-side** environment variables. Use only proxies you own or are explicitly authorized to use.
2. Copy the Render service URL, for example `https://hpn-browser-engine.onrender.com`.
3. Create a Vercel project connected to the `HPN` GitHub repository and set `ENGINE_URL` to that Render URL. Vercel will redeploy on every push.
4. Open the Vercel URL and start a browser session. The free Render plan may sleep when idle, so the first request can take time.

## Proxy configuration

`PROXY_LIST` accepts comma- or newline-separated URLs such as `http://host:port`, `https://host:port`, `socks4://host:port`, or `socks5://host:port`. `ROTATING_PROXY_URL` can be used for a provider-managed rotating endpoint. Credentials remain server-side and are redacted in the dashboard.

When no server-side proxy is configured, `AUTO_DISCOVER_PUBLIC=true` enables bounded discovery from maintained public-list sources. HPN parses HTTP/HTTPS/SOCKS4/SOCKS5 candidates into separate protocol catalogs, tests connectivity through an IP-echo service, records latency and exit metadata, removes duplicate exit IPs, and keeps only a small verified working pool. The catalog can be inspected with `GET /catalog`; the verified pool can be inspected with `GET /pool` and refreshed with `POST /pool/refresh`. `PUBLIC_PROXY_SAMPLE` and `PUBLIC_PROXY_TARGET` bound resource usage on the free Render instance; they default to conservative values rather than pretending that an unbounded internet-wide scan is reliable.

The repository includes `.github/workflows/render-keepalive.yml`, which runs every five minutes, checks `/health`, and requests a bounded catalog refresh. This is a best-effort wake-up mechanism; Render's free tier may still sleep or throttle a service, and a paid always-on plan is required for a hard uptime guarantee. Sessions also expire after `SESSION_TTL_MIN` minutes when idle, so abandoned Chromium processes do not consume the concurrent-session limit indefinitely.

If discovery finds no verified proxy, HPN clearly reports that fact; it will not claim that a new IP was found.

## Local checks

```bash
npm install
node --check engine/server.js
node --check api/engine.js
```

To run the engine locally, install its dependencies and launch it with `npm --prefix engine install && npm --prefix engine start`. Set `ENGINE_URL=http://localhost:10000` when running a local Vercel development server.

## License

MIT. Represented by Humayun Shariar Himu.
