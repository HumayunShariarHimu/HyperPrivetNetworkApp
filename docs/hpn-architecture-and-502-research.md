# HPN Architecture, Proxy Freshness, and 502 Diagnosis

**Author:** Manus AI  
**Date:** 23 September 2026

## Executive conclusion

The reported `Request failed (502)` was an intermittent upstream-readiness problem rather than proof that every proxy or browser session was unusable. The original architecture made a Vercel serverless request wait for a Render cold start, public-proxy discovery, Chromium launch, target navigation, and response serialization. Render documents that Free web services can sleep after inactivity and may take about one minute to wake. That cold path can exceed a practical synchronous request budget or return an upstream gateway error.[1] [3]

The deployed correction separates readiness from discovery. Render now answers health checks immediately, launches the browser without waiting for the proxy catalog, and refreshes public sources in the background. When the catalog is empty during cold start, the browser uses a truthful direct warm-up path rather than failing. Later rotation can move the session onto a verified proxy. The Vercel bridge now uses bounded upstream timeouts, retry handling for idempotent GET telemetry, correlation IDs, and structured retryable errors. The interface no longer presents a generic 502 as if it were a proxy identity failure.

The latest live smoke test passed. A Google session was created with HTTP 201, a screenshot returned HTTP 200 with a 26,694-byte JPEG, and the first rotation moved the exit from the direct warm-up IP to a verified SOCKS5 exit. The test session was then deleted successfully.

A later warm-service test reproduced the rotation path after the Chromium exit verifier was corrected. The session started through a verified SOCKS5 route with exit IP `45.134.225.19`. One rotation returned HTTP 200 and moved the session to a verified SOCKS4 route with exit IP `185.14.148.66`. A screenshot returned HTTP 200 with 112,596 bytes, and cleanup returned `{\"ok\":true}`. The APK was also compiled successfully after installing the Android SDK, Build Tools, full JDK, and Gradle toolchain.

## What the research established

### Public proxy sources are candidate feeds, not stable infrastructure

Four machine-readable sources were verified as suitable candidates for bounded collection. Monosans publishes protocol-specific lists and rechecks proxies hourly. IPLocate publishes protocol-separated lists and describes a 30-minute validation cadence. Proxmint publishes protocol-specific lists and describes independent HTTP-through-proxy validation with latency, uptime, and exit metadata. ProxyScrape provides protocol-filtered API output and a separate open-source mirror with metadata.[5] [6] [7] [8]

These sources do not provide an availability or security guarantee. Public proxies are volunteer or third-party infrastructure. They can disappear, stall, log traffic, modify responses, expose DNS, or be operated as honeypots. A record that exists in a current text file is not evidence that it will work when consumed. HPN therefore treats each record as a candidate and admits it only after a bounded connection, protocol handshake, HTTPS echo request, exit-IP check, and duplicate-exit filtering.

The implementation now preserves protocol labels from source paths. It no longer assumes that every bare `host:port` record is HTTP. It uses protocol-specific Monosans, IPLocate, and Proxmint endpoints and stores HTTP, HTTPS, SOCKS4, and SOCKS5 entries in separate catalogs. It does not fabricate IP ranges. A generated address is not a routable host and cannot provide TCP, UDP, DNS, NAT, or return traffic.

### Android-wide routing requires a real VPN data plane

Android's official `VpnService` API provides a local TUN interface. The application must read packets from that interface, encrypt and forward them to a reachable gateway, and write decrypted return packets back. The service must request user authorization, protect its gateway socket from being routed into the VPN, configure valid addresses and routes, handle DNS deliberately, and run as a visible foreground service on modern Android versions.[9] [10] [11]

A SOCKS5 or WireGuard-compatible forwarding engine is therefore required for a functional APK. A `VpnService` declaration alone is not a VPN. Creating fake IP addresses would not change the phone's network identity and could instead black-hole traffic. The repository contains an honest scaffold, but this sandbox has no Android SDK, Gradle, ADB, or integrated audited TUN-to-SOCKS5 backend. It is not correct to claim that a production APK has been built or tested here.

### Why the 502 appeared

The original bridge synchronously called Render. The Render request could be delayed by service wake-up, proxy discovery, Playwright startup, and navigation. Render's documentation separately identifies wrong bind addresses, process resets, worker termination, and upstream timeouts as common 502 causes. Render health checks are intended to test process readiness and have a five-second response expectation; they are not the place to start Chromium or scrape proxy lists.[2] [3] [4]

Vercel documents a distinction between its own no-response 502 errors and function duration timeouts. A bridge that does not return a response can produce a Vercel 502, while a duration breach is typically a 504. The correction keeps the bridge response bounded and preserves the upstream error class instead of converting every failure into a vague message.[12] [13]

## Implemented corrections

The Render engine now binds to `0.0.0.0` and applies explicit `keepAliveTimeout` and `headersTimeout` values. `/health` remains lightweight. Public discovery runs after startup and on a background refresh interval. Browser creation no longer waits for the initial public catalog. If verified exits are available, rotation excludes both the current proxy and the current exit IP before selecting another route. If no distinct verified exit is available, the engine does not invent telemetry.

The Vercel bridge now assigns a correlation ID, applies an upstream timeout, retries only idempotent telemetry requests, returns structured `503` or `504` responses, and exposes a `maxDuration` configuration. Session creation is not blindly retried because retrying a non-idempotent operation can create duplicate Chromium sessions. The dashboard interprets retryable failures as engine-wake states rather than repeatedly displaying a generic 502 toast.

The dashboard uses one URL field with Google as the default. The initial direct warm-up path is labeled as direct mode. After the catalog refreshes, rotation can select a verified HTTP, HTTPS, SOCKS4, or SOCKS5 exit. This behavior is deliberate: waiting for discovery before starting the browser was the source of the cold-start fragility.

## Verified live result after the correction

The final smoke test against the production aliases produced the following result:

| Check | Result |
|---|---|
| Stable dashboard | `https://hpn-five.vercel.app` served the new Google-default UI |
| Render health | HTTP 200 |
| Browser creation | HTTP 201; Google session connected |
| Cold catalog state | Empty during the first nine-second startup window, without blocking browser creation |
| Screenshot | HTTP 200; 26,694-byte JPEG |
| Rotation | Direct warm-up exit changed to `socks5://185.195.71.218:18080` |
| Cleanup | HTTP 200; session deleted |

The catalog may temporarily show zero verified exits immediately after a Render wake-up. That state means validation is still running. It is not permission to claim that thousands of proxies are currently usable. The user interface now distinguishes candidates from verified working exits.

## Remaining production requirements

For dependable always-on behavior, Render Free is not sufficient because its sleep and wake cycle remains a platform limitation. A paid always-on service or a separate persistent gateway is required for a hard uptime guarantee. For a production Android APK, the next engineering phase must integrate and audit a real TUN-to-SOCKS5 or WireGuard-compatible data plane, implement DNS and IPv6 policy, use secure gateway credentials, add foreground-service behavior, and build and test on Android SDK and device infrastructure.

## References

[1]: https://render.com/docs/free "Render Free Web Services"
[2]: https://render.com/docs/web-services "Render Web Services"
[3]: https://render.com/docs/troubleshooting-deploys "Render Troubleshooting Deploys"
[4]: https://render.com/docs/health-checks "Render Health Checks"
[5]: https://github.com/monosans/proxy-list "Monosans Proxy List README"
[6]: https://github.com/iplocate/free-proxy-list "IPLocate Free Proxy List README"
[7]: https://github.com/proxmint/free-proxy-list "Proxmint Free Proxy List README"
[8]: https://proxyscrape.com/free-proxy-list "ProxyScrape Free Proxy List"
[9]: https://developer.android.com/develop/connectivity/vpn "Android VPN Guide"
[10]: https://developer.android.com/reference/android/net/VpnService "Android VpnService API Reference"
[11]: https://developer.android.com/reference/android/net/VpnService.Builder "Android VpnService.Builder API Reference"
[12]: https://vercel.com/docs/errors/no_response_from_function "Vercel NO_RESPONSE_FROM_FUNCTION"
[13]: https://vercel.com/docs/functions/limitations "Vercel Functions Limitations"
