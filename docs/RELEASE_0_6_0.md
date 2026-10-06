# Connection Guard 0.6.0 qualification

**Release candidate; stable 0.5.1 remains published.** This page defines the gates
for the combined provider/Intel, custom-provider guide and Cloud-sync candidate.
It does not claim a completed competitive acceptance. The operational release
receipt records the exact source commit, archive SHA-256, CI and runtime results.

## Changed defaults and recipients

New installations use **ENFORCE**, so explicit VPN/proxy/Tor and Blackbox aggregate
listings can deny connections immediately. Blackbox includes hosting/cloud lists;
ProxyCheck/zowi hosting-only facts stay review. Select OBSERVE explicitly to
suppress actions. Geo starts Disabled. ip-check.net starts **disabled**, including
on new installations, until an operator explicitly opts in.

Sequential failover defaults on, including existing files without an explicit
strategy. Local Tor and signed Connection Guard Intel precede ProxyCheck →
Blackbox → zowi → IPQuery → IP-API; opted-in ip-check.net follows Blackbox.
Each reached external service receives the queried IP. Existing selections,
mode, keys, explicit timeouts and ordering are retained; additional recipients
and Intel are not enabled implicitly on old files. [Recipients and controls](PROVIDERS.md).

Cloud error reports default on with Cloud, including old files missing the
option. Disable `cloud.error-reports: false` or Cloud itself. No exception messages,
player identifiers or secrets enter error reports. [Disclosure](https://connectionguard.net/privacy#error-reports).
Intel downloads contact `intel.connectionguard.net` without player IPs and
verify signed list generations in the background. [Intel controls](LOCAL_DATA.md).

## Required artifact gates

- All combined regression tests, including real owned loopback Redis and Cloud
  command/client integration fixtures, without failures or skips.
- Main and optional-addon packaging, complete release history and version gates;
  descriptors must identify 0.6.0 and Java baselines remain unchanged.
- Byte-identical repeat builds of all three archives.
- The 63 developer benchmark contracts and 23 actual shaded-JAR core cases.
- Actual-JAR Java 8 fixtures for keyless parsing/failover, Intel authentication
  and background startup, timeout circuits, and manual Cloud-sync behavior.

These controlled component tests do not establish public-provider accuracy or
Minecraft login latency. Tests use owned loopback endpoints, not production Cloud
or queries with player IPs. Test telemetry and automatic list refresh are off.

## Runtime selection

Official metadata checked 6 October 2026 selects Paper **26.3 build 159**, Folia
**26.2 build 7**, Velocity **4.2.0 build 30** and **4.2.1-SNAPSHOT build 39**.
Minecraft's current stable release is **26.3**. Paper/Folia are upstream beta
builds, and Velocity 4.2.1 is a development snapshot. Stable metadata through
26.3 does not prove every intermediate Minecraft version.

Startup/help/reload/clean shutdown must use the same final candidate archive,
verified official runtime hashes and the runtime's Java requirement. Local
servers bind only to 127.0.0.1, run sequentially with at most 768 MiB, use fresh
directories and stop their owned process. Minecraft backends require a conscious
EULA decision covering the selected build. Reference Paper 1.21.11 build 132 and
Velocity 3.4.0 build 566 retain their earlier named fixture scope. These smoke
checks do not repeat all identity/forwarding/Bedrock or real-player scenarios.

## Timeout boundary and competitive gate

An individual HTTP timeout opens that provider's circuit immediately. Its later
calls skip locally until one half-open probe. Used-up quotas allocate no HTTP
work. The default attempt timeout is 1,500 ms; explicit values remain unchanged.
Available failover services continue to be tried as Gero selected. Thus multiple
previously untested hanging providers can reach the unchanged **5,000 ms** whole
deadline; a following login can discover another hanging provider. Once all
selected circuits are open, external providers are skipped locally.

The prior 42e1594 benchmark run 37526896017 recorded a residential timeout of
5,006.93 ms across four requests. That is **not** compliance with the proposed
universal first-login <2 s / subsequent-login <300 ms target. It remains a
disclosed release decision; no provider or unused fallback is treated as failed
merely because another provider timed out. Tests must include multiple hanging
providers and a healthy later fallback, not only a one-provider fixture.

The benchmark owner pins the **final source and JAR hash** and runs 692 addresses
with `proxycheck_key`, three rounds and all platforms. Detection must match the
best competitor, false positives must not increase, at least 95% of burst players
must be checked including failover, and local Tor must block across API errors.
Unchecked allowances must produce the periodic summary and status counters.
Warm latency is advisory as Gero selected. Disclose Intel/ground-truth overlap
and fixture provenance; neither proves independent held-out detection quality.

**No release tag, publication, listing update or release announcement before the
final benchmark acceptance and Gero's go.** Stop and back up the data directory
before replacing the JAR; keep a single main archive. Rollback restores both the
previous JAR and its backed-up data. Cloud remains optional and separately
disableable; [upgrade/configuration](CONFIGURATION.md).

Download and docs: https://connectionguard.net/download
