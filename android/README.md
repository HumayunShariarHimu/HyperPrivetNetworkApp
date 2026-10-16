# HPN Android native live-proxy VPN

The Android module is a native Kotlin `VpnService`; it is not a WebView wrapper. It vendors the MIT-licensed `hev-socks5-tunnel` engine and its JNI bridge. The service establishes an Android TUN interface, routes IPv4 and IPv6 device traffic through it, provides mapped DNS, and forwards traffic through a SOCKS5 endpoint.

## Automatic live proxy refresh

The app no longer asks the user to enter proxy addresses. **UPDATE LIVE PROXIES** fetches fresh candidates into memory from the configured public sources, deduplicates them, and reports source/candidate counts. The service refreshes the source feed before each scheduled rotation as well. Proxy candidates are not written to local storage.

The source catalog includes the user-requested pages and their machine-readable feeds where available:

- Proxifly SOCKS4 and SOCKS5 feeds
- ProxyScrape SOCKS5 feed
- SOCKSList.us
- SPYS.ONE SOCKS list
- Scrappey SOCKS5 list
- FreeProxy.World SOCKS5 list
- GitHub SOCKS5 topic listings

Only SOCKS5 candidates are used by the current native tunnel backend. SOCKS4 candidates are displayed and collected for visibility but are not passed to a SOCKS5-only data plane. HTTP/HTTPS proxies are intentionally not mislabeled as SOCKS5.

The source fetcher uses HTTPS, bounded connect/read timeouts, parallel source requests, IPv4 validation, port-range validation, and in-memory deduplication. A source failure is isolated and skipped; the app refuses to connect when no live SOCKS5 candidate is available.

## Rotation

Choose the interval in the native black terminal-style interface, from 5 seconds up to 1 hour. **START FULL-DEVICE VPN** establishes the TUN interface. Each timed rotation refreshes the live pool, performs a fresh SOCKS5 CONNECT plus IP-echo preflight on the candidate, then hands over the native TUN-to-SOCKS5 tunnel. The native backend is a single instance, so this is not a zero-packet-loss make-before-break handover: a short interruption can occur while the native tunnel is restarted. The old endpoint is attempted again if the replacement tunnel fails to start. **ROTATE NOW / REFRESH FEED** performs the same refresh-before-rotation path immediately.

Public free proxies are not a reliable production gateway. They may be dead, overloaded, incompatible, or controlled by an unknown party. Use only traffic and gateways you are authorized to use. The app does not generate fake public IPs and does not silently fall back to a direct route.

## Build and test

A reproducible APK build requires Android SDK 35, NDK `29.0.14206865`, JDK 21, and Gradle.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug
```

In the current execution environment, Gradle could not be downloaded because outbound DNS/network access was unavailable, so APK compilation and signing have **not** been verified in this run. A physical Android device and ADB-connected test phone were not available either. Device-level packet-flow, DNS-leak, IPv6, rotation continuity, and battery/background behavior therefore remain unverified and require acceptance testing on a real device.

## Native source

The vendored tunnel implementation is from [heiher/sockstun](https://github.com/heiher/sockstun) and its [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) dependency. The HPN service binds the JNI entry points to `com.hpn.app.HpnVpnService`, writes only a transient per-endpoint YAML configuration, excludes the HPN process from its own VPN route, and runs as a foreground service.
