# ConnectivityAndInternetAccessTest

Runnable Android reference project and review harness for [`ConnectivityAndInternetAccess`](https://gist.github.com/rodrigosambadesaa/729cca29a031fef4e2f15751863b655f), a modernized connectivity and Internet-reachability utility for Android.

The project exists for two reasons:

1. to keep a complete Android application synchronized with the current Gist implementation, so the utility can be built and exercised in a real app; and
2. to provide a concrete target for deeper review of Android network-state handling, active reachability diagnostics, routing, VPN behavior, timeouts, concurrency, IPv4/IPv6, and API-level compatibility.

Feedback, counterexamples, issues, and pull requests are welcome — especially from developers with Android platform or networking experience.

---

## Design model

The implementation deliberately treats these as different questions:

```text
Observed Android network state
            ↓
Actual application/backend request
            ↓
Optional active diagnostics when useful
```

### 1. Observed network state

Cheap, passive Android state answers questions such as:

- Is there a usable network?
- Has Android recently validated Internet access?
- Has Android detected a captive portal?
- What transport is present: Wi-Fi, cellular, Ethernet, VPN?

`NetworkObserver` uses `NetworkCallback` on modern Android and a legacy connectivity receiver on older code paths. It does **not** perform DNS or HTTP probes merely to keep state updated.

### 2. The real backend request

If an application needs to know whether **its own service** is reachable, the real request to that service remains the authoritative answer.

The generic diagnostics in this project are not intended to become a mandatory pre-flight check before every API request. A network can change immediately after any connectivity test, and successful reachability to a public endpoint does not prove that a particular backend is healthy or reachable.

### 3. Optional active diagnostics

When an application explicitly wants more information — for example after a network error, during troubleshooting, or in a diagnostic screen — the utility can perform bounded active probes across multiple protocols.

---

## What the test app currently exercises

The Compose test screen includes independent controls for:

- **Passive network observation**
- **Default active Internet check**
- **Strict captive-portal-aware check**
- **ICMP reachability**
- **TCP reachability**
- **NTP reachability**
- **TLS reachability**
- **IPv6-only reachability**
- **Connection-attempt tracking**
- **Wi-Fi / mobile / Ethernet / VPN transport state**

The app displays the endpoint/probe that succeeded, elapsed time, and attempted endpoints where applicable.

---

## Signals and what they mean

| Signal | What it can tell you | What it does **not** prove |
|---|---|---|
| `NET_CAPABILITY_INTERNET` / usable network | Android exposes a network intended for Internet traffic | That public Internet or your backend is currently reachable |
| `NET_CAPABILITY_VALIDATED` | Android most recently validated general Internet access on that network | That connectivity is still working now, or that your backend is reachable |
| `NET_CAPABILITY_CAPTIVE_PORTAL` | Android detected a captive portal | That every application request will fail |
| Effective DNS | Name resolution through the selected Android `Network` works | That TCP, TLS, HTTP, or your backend works |
| Direct DNS | A configured DNS resolver can be reached and returns a valid DNS response | That Android's effective resolver path or application service works |
| TCP | A configured host/port accepts a TCP connection | That TLS, HTTP, or an application protocol works |
| NTP | UDP/123 reaches a configured NTP server and receives a plausible response | That general Internet access is unavailable if NTP fails; UDP/123 is often filtered |
| TLS | A real TLS handshake completes against a configured endpoint | That a particular API/backend is healthy |
| HTTP(S) | A configured HTTP(S) endpoint responds according to the selected probe strategy | That arbitrary Internet destinations or your own backend are reachable |
| ICMP | A target answers ping | That Internet is unavailable when ping fails; ICMP is frequently filtered |
| IPv6-only target | The device can reach that target over IPv6 | That all IPv6 destinations are reachable or perform well |

---

## Active check pipeline

The current default reachability check is a bounded, staged diagnostic rather than the old sequential `TCP/80` loop used by the historical implementation.

At a high level:

1. Prefer DNS resolution through the application's effective Android `Network`.
2. If needed, race configured **DNS, TCP, and NTP** transport-level probes.
3. If none succeeds, race configured **HTTP(S) and TLS** application-level probes.
4. Return the first successful probe and cancel remaining work.
5. Keep the whole operation within a bounded global deadline.

The result is an `InternetResult` containing:

- `isReachable()`
- `getReachedHost()`
- `getAttemptedHosts()`
- `getElapsedMilliseconds()`

Probe labels identify the successful path where possible, for example `dns://`, `tcp://`, `ntp://`, or `tls://`.

### Default diagnostic targets

The implementation currently includes a mixture of IPv4, IPv6, DNS, TCP, NTP, HTTP(S), and TLS targets. They are configurable through the instance `Builder` and should be treated as generic diagnostics rather than application-specific service checks.

---

## Android `Network` routing

Where the Android API permits it, probes use the selected application `Network` rather than blindly relying on process-default routing:

- DNS resolution can use `Network.getAllByName(...)`.
- TCP/TLS sockets can be bound to the selected `Network`.
- UDP DNS/NTP sockets are bound where supported by the platform API.

This is particularly important when reasoning about:

- VPNs;
- multiple simultaneous networks;
- Wi-Fi vs cellular transitions;
- effective DNS behavior;
- network-specific diagnostics.

ICMP is intentionally separate because the spawned `ping` process follows OS routing and cannot be bound to an Android `Network` in the same way as the socket-based probes.

---

## IPv6

IPv6 is treated explicitly rather than assumed through dual-stack hostnames.

The defaults include IPv6 diagnostic targets, and the test app contains an **IPv6-only TCP check** using:

```text
[2606:4700:4700::1111]:53
```

Because that diagnostic contains no IPv4 alternative, an IPv4 success cannot accidentally be reported as IPv6 reachability.

---

## Captive portals

Android's passive `CAPTIVE_PORTAL` capability and an explicit strict check are kept separate.

`strictCaptivePortalBuilder()` disables DNS, TCP, NTP, and TLS stages and uses the expected `204` behavior from Android's connectivity-check endpoint. This prevents another successful generic probe from making a strict captive-portal check appear successful.

---

## ICMP is diagnostic only

ICMP deliberately does **not** participate in the normal Internet result.

A network may provide perfectly usable DNS, HTTPS, and backend connectivity while filtering ping. Therefore:

```text
ICMP failure != Internet unavailable
```

The class exposes a separate `IcmpResult` for this reason.

---

## Instance configuration

New code should prefer the instance-based `Builder` API over the legacy mutable-global compatibility API.

Example in Java:

```java
ConnectivityAndInternetAccess connectivity =
        new ConnectivityAndInternetAccess.Builder()
                .setHosts(Arrays.asList(
                        "https://example.com/health",
                        "https://example.org/"))
                .setDnsResolvers(ConnectivityAndInternetAccess.defaultDnsResolvers())
                .setTcpTargets(ConnectivityAndInternetAccess.defaultTcpTargets())
                .setNtpTargets(ConnectivityAndInternetAccess.defaultNtpTargets())
                .setTlsTargets(ConnectivityAndInternetAccess.defaultTlsTargets())
                .build();

ConnectivityAndInternetAccess.Request request =
        connectivity.checkInternetAsync(context, result -> {
            if (result.isReachable()) {
                Log.d("Connectivity", "Reached: " + result.getReachedHost());
            }
        });

// If the caller no longer needs the result:
request.cancel();
```

Custom probe strategies can also be supplied through:

- `setDnsProbeStrategy(...)`
- `setHttpProbeStrategy(...)`
- `setTcpProbeStrategy(...)`
- `setNtpProbeStrategy(...)`
- `setTlsProbeStrategy(...)`

---

## Passive observation example

```java
ConnectivityAndInternetAccess.NetworkObserver observer =
        ConnectivityAndInternetAccess.observeNetwork(context, state -> {
            boolean connected = state.isConnected();
            boolean validated = state.isInternetValidated();
            boolean captivePortal = state.isCaptivePortalDetected();
        });

// Close with the owning lifecycle.
observer.close();
```

The observer reports Android/system state only; it does not continuously generate active probe traffic.

---

## Running this project

This repository is an Android application, not a published Maven artifact.

Current app configuration:

- **minSdk:** 24
- **targetSdk:** 37
- **compileSdk:** 37
- **Java source/target:** 11
- UI: **Jetpack Compose / Material 3**

Open the project in a compatible Android Studio version and run the `app` configuration on an emulator or physical device.

From a shell, a debug build can also be produced with:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

> **Note:** `ConnectivityAndInternetAccess.java` contains compatibility paths for Android versions older than the test application's current `minSdk`. This repository's current app configuration does not itself exercise devices below API 24, so older paths should not be considered verified merely because the source contains them.

---

## Project structure

```text
ConnectivityAndInternetAccessTest/
└── app/src/main/java/com/example/connectivityandinternetaccesstest/
    ├── ConnectivityAndInternetAccess.java   # Utility under review
    └── MainActivity.kt                      # Runnable diagnostic/test UI
```

The repository is kept in sync with the modern Gist implementation:

- **Current successor Gist:** https://gist.github.com/rodrigosambadesaa/729cca29a031fef4e2f15751863b655f
- **Historical preserved 2020 version:** https://gist.github.com/kibotu/c3e57522f489d2e4d0d38c261f5b0220

The historical version is useful for understanding the evolution of the implementation, but it is **not recommended for new projects**.

---

## Historical context

This code ultimately descends from `Connectivity.java` by **Emil Davtyan (`emil2k`)**, later modified by **str4d**, and then substantially extended and redesigned.

The 2020 generation attempted to modernize Android connectivity handling but accumulated several limitations, including combinations of legacy and modern APIs, sequential TCP/80 host tests, blocking behavior around `AsyncTask.get()`, duplicated capability logic, API 21/22 edge cases, and mutable global configuration.

The current design is intended as a redesign rather than a patch on top of that architecture:

- passive network state is separated from active reachability;
- Android validation is exposed as a signal rather than treated as permanent proof of reachability;
- actual backend requests remain authoritative for the service an app cares about;
- active probes are opt-in diagnostics;
- probes are concurrent, bounded, cancellable, and network-aware where possible;
- instance-based configuration is preferred;
- IPv6 and multiple protocol families are explicit.

---

# Review requested

Deep technical review is explicitly welcome.

If you have Android platform, networking, VPN, DNS, IPv6, or concurrency experience, the most useful feedback would be attempts to find cases where the model produces a misleading result.

In particular:

1. **Android `Network` selection**  
   Are the API-level branches and selected-network semantics correct, particularly around API 21/22, default networks, VPNs, and network transitions?

2. **Connected vs validated vs reachable**  
   Is the separation between a usable Android network, `NET_CAPABILITY_VALIDATED`, captive-portal state, and fresh active reachability conceptually correct?

3. **Probe value and redundancy**  
   Do DNS, TCP, NTP, HTTP(S), and TLS provide useful independent diagnostic information? Which probes would you remove, add, or reorder?

4. **Network binding**  
   Are socket binding and DNS resolution correctly scoped to the selected Android `Network` where the platform supports it?

5. **VPN edge cases**  
   Are there Android VPN configurations where the selected network or capability state can make these diagnostics misleading?

6. **IPv6 / dual stack**  
   Are endpoint parsing, address resolution, and explicit IPv6 diagnostics correct for IPv6-only and dual-stack environments?

7. **Concurrency and cancellation**  
   Are the executor strategy, racing probes, interruption handling, cancellation, and global deadline robust under concurrent callers?

8. **False positives / false negatives**  
   What real-world network configurations can still produce misleading `InternetResult` values?

9. **Captive portals**  
   Is strict mode sufficiently isolated from the generic diagnostic probes?

10. **API design**  
    Is the `Builder`/result/callback API appropriate for a reusable utility, and which legacy compatibility APIs should eventually be removed?

Please open an **Issue** for architectural discussion or a **Pull Request** for concrete changes. Small, focused counterexamples are especially useful.

---

## Important limitation

No generic connectivity utility can guarantee that an arbitrary application service is reachable.

A successful public DNS/TCP/TLS/HTTP diagnostic can become stale immediately, and a particular backend can fail while the rest of the Internet remains healthy. Conversely, a failed optional diagnostic such as ICMP or NTP can be caused by protocol-specific filtering while normal application traffic still works.

The intended model remains:

```text
observe network state
        ↓
make the actual request
        ↓
run deeper diagnostics only when they add value
```

---

## License and attribution

`ConnectivityAndInternetAccess.java` declares **SPDX-License-Identifier: MIT** and preserves attribution to the earlier lineage from Emil Davtyan (`emil2k`) and `str4d`.

If you reuse the source, preserve the applicable MIT notice and attribution.
