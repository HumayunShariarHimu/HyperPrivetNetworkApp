# HPN Android VPN Architecture Research

## Findings

1. Android's documented VPN connection order is: call `VpnService.prepare()`, protect the upstream tunnel socket with `VpnService.protect()`, establish the upstream connection, configure `VpnService.Builder`, then call `establish()`. The existing native engine created SOCKS sockets but had no Java `protect()` callback. That can route the upstream SOCKS connection back into the TUN interface and make the app appear connected while traffic fails.
2. Android requires at least one address and an open route such as `0.0.0.0/0` or `::/0` for full-device routing. The project already creates both address families and routes.
3. Android allows only one active VPN per user/profile, and `establish()` can return null when the app is not prepared or permission is revoked. The UI must therefore reflect service broadcasts and failures instead of assuming the tap succeeded.
4. SOCKS5 negotiation requires a version/method greeting, method-specific authentication if selected, then a CONNECT request with `VER=5`, `CMD=1`, `RSV=0`, an address type, destination address and network-order port. The implementation validates the no-auth greeting and CONNECT reply, then performs a short HTTP response check.
5. Public proxy feeds are volatile. A global random sample can miss live endpoints even when a source contains working proxies. Candidate selection must sample each source fairly, bound all timeouts, and log source status, parsed candidate counts, probe counts and the final result.

## Implementation consequences

- Add a JNI callback from the native socket creation path to `HpnVpnService.protectSocket(fd)`.
- Fail a native socket creation if Android cannot protect it.
- Keep source fetches and probes bounded and source-balanced.
- Keep a compact diagnostic log visible so a user can distinguish fetch failure, parse failure, no live proxy, VPN permission denial and native startup failure.

## References

[1]: https://developer.android.com/develop/connectivity/vpn "Android VPN developer guide"
[2]: https://developer.android.com/reference/android/net/VpnService "Android VpnService API reference"
[3]: https://developer.android.com/reference/android/net/VpnService.Builder "Android VpnService.Builder API reference"
[4]: https://developer.android.com/about/versions/14/changes/fgs-types-required "Android 14 foreground service type requirements"
[5]: https://www.rfc-editor.org/rfc/rfc1928 "RFC 1928: SOCKS Protocol Version 5"
[6]: https://www.rfc-editor.org/rfc/rfc1929 "RFC 1929: Username/Password Authentication for SOCKS V5"
