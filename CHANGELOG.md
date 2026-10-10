# Changelog

## Unreleased — 0.6.1

### Custom webhook message variables

- Expand existing TEXT templates (`messages.vpn-webhook` and `messages.geo-webhook`) with `%UUID%`, `%IS_VPN%`, `%LOCATION%`, `%COUNTRY%`, `%CITY%`, `%ISP%` and UTC `%TIME%`, alongside `%NAME%` and `%IP%`. Operators choose exactly which fields to send and their order; preserve customized files, existing triggers, EMBED output and privacy controls.
- Render once literally from captured identity/check/location facts after the admission decision. Unknown or unchecked data stays UNKNOWN; no extra geo/API lookup, offline UUID fabrication or new Cloud/addon data field. Keep safe reload retirement, bounded delivery and mention suppression. [Template setup](docs/WEBHOOKS.md#custom-messages-and-variables).

### Everyday joins

- Put a bounded 10,000-entry Memory tier in front of SQLite and coherent Redis. Fresh cache hits and local-list results complete on the caller thread; persistence reads happen only on Memory misses. Keep fact age, source expiry, namespace isolation and invalidation fences.
- Use SQLite WAL/NORMAL, reusable statements, separate reads and coalesced background transactions every second or 100 pending facts. Write pressure drops best-effort copies without reporting CACHE_ERROR; clean shutdown flushes accepted writes. A crash can lose the last uncommitted cache batch.
- Track Redis invalidations using an optional Redis 6+ client-tracking connection. Unsupported server/ACL configurations retain remote reads while Redis is online; offline bounded Memory fallback and reconnect behavior remain. Explicit connected clears wait for the remote deletion.
- Build decision reports/webhook payloads on the bounded observer workers after releasing the login policy lease. Preserve the final decision, duration and inline unchecked-admission counters; reports remain best effort under pressure.
- Bundle and isolate the existing BoostedYAML 1.3.6 runtime; construct Libby only on demand for optional database drivers.
- Initialize optional SQLite/Redis libraries and persistence off the Velocity enable thread. Early logins use Memory and continue the normal source chain; /cg doctor shows persistence as starting or unavailable. Preserve early fact timestamps when handing them to persistence. Memory/Disabled cache modes are unchanged.
- These are development changes for 0.6.1. Same-run, three-round comparative performance, detection, failure and Redis acceptance is required before publication. [Performance scope](docs/PERFORMANCE.md).

### Competitor migrations

- Automatically detect FoxGate, ProxyShield, VPNGuard, KauriVPN and AdvancedAntiVPN configuration folders. Preview compatible mode/country/provider-key settings and explicit IP/CIDR/range/UUID/ASN admin rules; reviewed console application stages a validated migration for the next restart.
- Read known SQLite and Kauri H2 admin-list schemas locally, without importing provider caches, name-prefix bypasses, foreign commands or remote database credentials. Unknown formats/conditional modules require an explicit complete local export; Java 11+ is required for the on-demand H2 reader.
- Keep CG failover/local detection, validated runtime limits and individually configured CG fields. Disclose changed scoring/hosting/identity/country-source semantics and final enabled IP recipients before apply; no equivalent/improved benchmark result is claimed.
- Preserve source files and private backups; detect stale plans/active competitor JARs and recover interrupted commits without overwriting external edits. Existing Cloud overlays/versioned policies require explicit release. [Migration guide](docs/MIGRATIONS.md).


Website and downloads: [Connection Guard](https://connectionguard.net) · [Download and docs](https://connectionguard.net/download).

### False-positive handling and signed Intel proxy data

- Require fresh local Connection Guard Intel HOSTING membership to confirm a Blackbox `Y` by default. Otherwise return UNKNOWN and continue normal failover. `provider.vpn.blackbox.require-confirmation: false` restores the broader 0.6.0 behavior. Explain and decision source observations identify an unconfirmed listing; no extra confirmation HTTP request is made.
- Disable VPN IP-API only in the new-install template. Existing choices remain untouched; HTTP-only IP-API remains an explicit non-commercial opt-in, last in failover.
- Load optional signed `additional_lists.PROXY` data without changing the four required manifest lists. Fresh proxy membership is positive and takes priority over RELAY ALLOW. Invalid or unavailable optional data is skipped with visible status while valid base lists still activate. All accepted files share the atomic generation and rollback protection; no player IP is sent to Intel.
- Show proxy availability, entry counts and signed publication time in local/provider diagnostics; preserve existing Cloud PROXY type/data-time fields. Invalidate older cached Blackbox decisions and partition cache identity when optional proxy availability changes.
- Add signed loopback/response regressions and a contract test that executes the byte-for-byte frozen 0.6.0 manifest validator. Final independent candidate detection/failure/performance acceptance remains pending; these tests do not establish a comparative accuracy claim.

## 0.6.0 — 2026-10-06

Published at the maintainer's request; the full exact-artifact competitive benchmark remains pending. [Release notes and checksums](https://github.com/gerolndnr/connection-guard/releases/tag/0.6.0).

- New installations use ENFORCE and can immediately block VPN/proxy/Tor evidence and Blackbox aggregate listings; Geo stays Disabled. ip-check.net is opt-in and defaults off. Existing modes and provider choices are retained.
- Anonymous error reports are on with Cloud; disable `cloud.error-reports`. [Published disclosure](https://connectionguard.net/privacy#error-reports).
- Bound external HTTP attempts to 1500ms by default and open a source circuit on its first timeout; preserve explicit timeout choices, source failover and existing pool/pause/login limits. Local quota skips allocate no transport work.
- Load saved signed Intel bundles asynchronously at platform startup and keep Intel UNKNOWN until ready. Immutable local membership bypasses HTTP workers and shares one parsed address across four indexes.

- Add built-in **Connection Guard Intel** before VPN APIs on new installations;
  existing configurations require explicit opt-in. Daily background fetches from
  https://intel.connectionguard.net/ send no player IP. Verify an ECDSA P-256
  signed manifest, SHA-256, sizes/counts and activate all four local indexes
  together; failed updates retain the last good generation. Fresh VPN/TOR hits
  stop VPN failover, missing/unlisted/stale data is UNKNOWN after72h, HOSTING is
  review only, and RELAY defaults to ALLOW with a VPN option. Explicit VPN/TOR
  takes priority over overlapping relay ranges. Explain and Cloud retain type
  and signed publication time. Managed Intel enabled/relay switches and the
  agreed optional source fields `types`/`data_as_of` require the Cloud-first
  schema deployment. Full exact-candidate benchmark acceptance remains pending.

- General sequential VPN failover defaults on for every selected keyed, anonymous, custom HTTP or extension source. Configure provider.vpn-failover.enabled/order; disabling restores parallel voting and the stored required-positive-flags. Existing modes, provider selections and keys are retained. A mode/order change invalidates old fact namespaces while preserving selected source quota usage.
- New installs use ENFORCE with local Tor → signed Connection Guard Intel → anonymous ProxyCheck v2 (`vpn=1`) → Blackbox → zowi → IPQuery → IP-API. ip-check.net is off by default; opt-in places it between Blackbox and zowi. Existing provider selections, order and enforcement modes remain unchanged; a durable once-only upgrade recommendation requires explicit opt-in to the new recipients.
- Add native keyless HTTPS adapters `blackbox`, `ipcheck`, `zowi`, each capped locally at 60 requests/minute by default. Malformed/unknown answers never count as clean; the original 5,000 ms login deadline and attempt limit apply. Blackbox Y also covers hosting/cloud lists and the explanation names this broader reason; zowi hosting-only remains UNKNOWN review evidence.
- The new fallback services receive player IPs. Blackbox is operated by Cameron Munroe / ipinfo.app, has published privacy information but no written terms; ip-check.net publishes no operator, terms or privacy policy; zowi is operated by the developer of competing FoxGate. Include recipients in server privacy information before opting in. [Recipients, controls and sources](docs/PROVIDERS.md#new-keyless-recipients-in-060).
- Expose only explicit new-provider enabled switches to managed Cloud settings and stable health/event IDs `vpn-blackbox`, `vpn-ipcheck`, `vpn-zowi`. Event enums remain unchanged; the Intel addendum agrees optional types/data_as_of source fields; the dashboard must label a positive Blackbox source as the broader list. Dashboard allowlist/display deployment is coordinated separately before merge.
- An embedded Tor bulk snapshot is checked locally before caches/APIs. Periodic list refresh never sends a player's IP. Stale lists remain visible in doctor and retain Tor protection, with documented false-positive/staleness limits.
- Use ProxyCheck v2 with `vpn=1`, officially supported until 2035, for VPN verdicts including retained v3 configurations. Share the same answer and quota reservation with ProxyCheck geo; retain hosting-only review without a VPN-operator name list. Normalize IPv6 requests and equivalent response keys.
- Add sanitized recorded responses for all 18 VPN misses in the 140-subject candidate sample and 52 unflagged residential/mobile controls; these regression fixtures do not establish full competitive detection acceptance.
- Surface local/remote quota exhaustion and circuit outages. Count CG-allowed UNKNOWN VPN logins independently of observers, show totals/reasons in doctor and Cloud status, and warn every five minutes. Preserve configured circuit pauses and Retry-After.
- Retain established HTTP timeout, worker, queue, inflight and circuit defaults pending comparative load/failure evidence; new-install Geo stays Disabled.
- Redis outages at startup use a bounded Memory cache and background reconnect. Reload/invalidation cannot wait on the reconnect probe.
- VPN kick messages tell players to ask staff for a scoped `/cg allow` exception.
- Add `/cg cloud sync` for linked operators to fetch/apply dashboard settings immediately on the background worker, with a shared ten-second cooldown, version/result replies and regular scheduling preserved. Advertise `sync_command`; Cloud 429 preserves the pending batch without increasing transport-error backoff.

- Make the optional Cloud Dashboard prominent with a framed native-colored console notice and a clickable staff/operator join hint, once per installation across clean restarts. Forwarded backends defer in-game hints to the proxy; linked/disabled Cloud stays quiet.

- Implement separately authorized local policy activation/rollback with reviewed candidate/base hashes, a bounded private history, one atomic decision-settings/rules document and pending-login guards. Native activation/restart/rollback qualification passes on four pinned loopback platforms; no stable release has been published. Existing dashboard rule writers use the same journal, while conflicting decision-field ownership is rejected.
- Send bounded anonymous metadata for Connection Guard's own exceptions with regular Cloud sync, without exception messages, player data or foreign frames. `cloud.error-reports: false` or any Cloud off switch disables capture and reporting; older Clouds receive the same pending sync without the optional field. [Data and controls](docs/PRIVACY.md).
- Add bounded local synthetic policy replay using the same VPN/geo evaluator as live platform checks. Candidate comparisons perform no lookups, actions or activation; literal DENY rules added during a pending lookup take precedence over earlier permission exemptions.
- Add an explicitly enabled, in-memory live policy shadow comparison. It reuses final policy facts, retains counters rather than observed identities, and stops on reload, rule changes, expiry or its sample limit. It cannot activate candidates or roll back a policy.

The full comparative benchmark acceptance remains open. Hosting-only facts from ProxyCheck/zowi do not block, but the separately approved Blackbox aggregate list includes hosting/cloud. No stable release or accuracy claim is made by this candidate.

## 0.5.1 — 2026-10-05

- Initialize Velocity bStats with the verified project ID 22913, bundle its injected factory and stop its Metrics instance on shutdown. Statistics errors keep connection checks active; global bStats opt-out remains available.
- Pin the unchanged MIT LimboAPI 1.1.26 compilation archive and verify its SHA-256, so clean addon builds do not depend on the unavailable upstream Maven path. It remains compile-only and is excluded from all runtime JARs.
- Link the plugin descriptors and documentation to the canonical Connection Guard website.
- Replace the README cartoon logo and remove obsolete listing graphics with unverified protection/performance claims.
- Add a reproducible developer benchmark suite with artifact-bound policy, provider-failure and recovery checks in CI, native comparison receipts and a preregistered latency-study preflight. These tools do not change detection behavior or establish better detection accuracy or measured performance.

Upgrade from 0.5.0: stop the server/proxy, replace the main JAR with `connection-guard-0.5.1-all.jar` and restart; keep only one main JAR. Configuration and the Cloud protocol/defaults are unchanged. Cloud remains optional and on by default; [privacy and controls](https://connectionguard.net/privacy#plugin). [Release checks and limits](docs/RELEASE_0_5_1.md).

Download and docs: https://connectionguard.net/download

## 0.5.0 — 2026-10-04

### Rules, providers and reliability

- New scoped allow/deny/exempt rules for IPv4/IPv6, CIDR and trusted UUIDs, with persistent time limits. Explicit DENY retains priority over ALLOW; an expired rule never grants access.
- Source-specific country, ASN, ISP/operator, classification, risk and confidence selectors; explain output keeps missing and conflicting evidence separate.
- Attributed local address lists and optional Geo/ASN MMDB sources with bounded imports and freshness checks.
- Optional native IPQualityScore (off by default), ProxyCheck v2/v3 selection and exact decimal risk in local decisions, caches and API observations.
- Whole-login deadlines, bounded transport/cache queues, shared lookups, circuit pauses and local provider request budgets. UNKNOWN is explicit and follows the scoped OPEN/OBSERVE/CLOSED policy.
- Optional login admission/cooldown limits; SQLite/Redis namespaced rich-fact storage and Redis TLS selection.
- Validated whole-draft reloads preserve active settings on rejection, account for native permissions/current connection identity and run Bukkit/Folia work on the correct platform scheduler.
- Versioned provider, observer and read-only admission APIs. Separate optional LibertyBans and native challenge adapters retain their explicit selection, licensing and documented test limits.
- German and Spanish messages alongside English; custom files remain intact. Rich decision webhooks use actual completed facts, bounded delivery, mention suppression and private defaults.
- Bundle and relocate Gson and the bStats runtime; keep the 0.4.11 hotfix in actual source history. All platform versions derive from the same build value.

### Optional Cloud dashboard

- Free dashboard at https://app.connectionguard.net for decisions, provider health, quotas, network rules and supported configuration.
- **Cloud is on by default.** Until linked, it sends anonymous installation/platform information, health and aggregate counters; no player IPs or UUIDs. After linking and accepting processing terms, it sends individual decisions with IPs and trusted UUIDs. [Full privacy disclosure](https://connectionguard.net/privacy#plugin) and [plugin controls](docs/CLOUD.md).
- Turn it off with `cloud.enabled: false`, `/cg cloud disable` (persistent) or `CONNECTIONGUARD_CLOUD=false`. Logins never wait on Cloud; bounded background sync handles outages.
- Remote settings are validated atomically and layered over local config without rewriting `config.yml`. Rejected drafts keep the old configuration. Remote console-command execution is unavailable.
- Time-limited dashboard rules report `rule_expiry`, preserve absolute deadlines locally and stop matching offline. Already-expired commands are acknowledged without creating a rule.
- Advanced IPQualityScore/rich-webhook settings remain local; protocol v1 uses the legacy integer risk display. Exact local policy risk remains unchanged.

### Upgrade and rollback

Back up the plugin directory and stop the server/proxy. Replace the old JAR with `connection-guard-0.5.0-all.jar`; keep only one main JAR. Restart and inspect `/cg doctor` and `/cg cloud status`.

**New installs start in OBSERVE with an empty country blocklist.** Existing files without `operation.mode` retain ENFORCE. Existing translations, provider keys and actions are preserved. Compare new options with the bundled template; do not replace your config blindly. Native IPQualityScore, local data and optional integrations are disabled until selected.

New rich-fact cache namespaces intentionally do not reuse older Boolean-only entries; expect provider lookups while the new cache warms. Provider budgets are process-local estimates, not account-wide remaining quota. Review provider terms before use.

To roll back, stop the server/proxy, restore the backed-up 0.4.11 plugin directory and its JAR, and restart. 0.4.11 does not understand Cloud, managed access rules or the new native integrations; restore its configuration and remove optional 0.5 addons. Disable Cloud before downgrade if desired.

### Qualification

Reproducible builds, structured regression reports, package/version-history gates and selected native runtime tests qualify the exact release artifact. [Final release scope](docs/RELEASE_0_5_0.md) records the tests and limits. Synthetic login fixtures do not prove authenticated Java/Bedrock account support, every Minecraft version, provider accuracy or production pilot outcomes.

Verified identity-bound challenge grants, complete replay/shadow/rollback and persistent account-wide API budgets are future work; this release does not claim those full differentiation contracts.

## 0.4.11 — 2026-10-03

Urgent maintenance hotfix based on published 0.4.10.

- Bundle and relocate bStats Base 3.0.2. Its missing `MetricsBase` class could prevent Connection Guard from enabling on Bukkit/Paper and BungeeCord.
- Verify the bStats runtime classes in the combined JAR. Existing configuration and detection behavior are retained.

Stop the server/proxy, replace the Connection Guard JAR with `connection-guard-0.4.11-all.jar`, and restart. Do not keep both JARs installed.

Validation: regression tests and combined artifact verification; a controlled Paper 1.21.11 build 132 startup/enable/shutdown test qualifies the packaging fix. This hotfix adds no new platform compatibility claim.

## 0.4.10 — 2026-10-02

Maintenance release for Spigot, BungeeCord and Velocity.

### Fixes

- Wait for cache initialization before registering connection checks and commands. A failed or stalled cache setup stops plugin initialization with a bounded timeout.
- Retry VPN detection after incomplete provider responses instead of caching an unreliable negative verdict. Healthy provider votes still count towards the configured threshold.
- Bundle and isolate OkHttp, Okio and Kotlin dependencies so HTTP detection does not depend on libraries supplied by another plugin.
- Reuse a bounded HTTP client, close responses, reject unsuccessful HTTP responses and malformed/missing detection fields, and keep provider URLs, API keys and response messages out of failure logs.
- Use HTTPS for ProxyCheck and IPHub requests. The free IP-API endpoint remains HTTP.
- Cache successful geo lookups and treat failed geo lookups as unavailable so later queries can retry. IP-API error responses no longer require location fields.
- Read both custom-provider nested fields using the documented `#` separator, preserve colons in header values and substitute `%IP%` in headers.
- Substitute message placeholders literally, including ISP names containing dollar signs or backslashes.
- Restore reproducible builds, verify all three plugin descriptors and runtime HTTP dependencies, and run regression tests in CI.
- Preserve Velocity's pre-login authentication mode when Connection Guard allows a connection.

### Upgrade

Stop the server/proxy, replace the existing Connection Guard JAR with `connection-guard-0.4.10-all.jar`, and restart. Existing configuration and translations are retained. Provider and cache changes require a restart; `/cg reload` reloads configuration and messages.

Older versions may have cached negative VPN verdicts during a provider outage. After upgrading, use `/cg clear <IP>` for affected addresses or `/cg clear` to clear the whole cache (permission: `connectionguard.command.clear`). Clearing the cache also clears geo entries and increases provider requests until it fills again.

The existing availability policy is unchanged: missing VPN votes do not count as positive votes; if the threshold is not met, the connection proceeds. A missing geo response supplies no geo verdict. This is not a fail-closed mode.

### Validation and compatibility

- 48 Java 8 regression tests, including local HTTP fixtures, thresholds, provider recovery, geo caching and cache startup.
- Combined JAR packaging and Java bytecode checks: Java 8 for core/Spigot/BungeeCord, Java 17 for Velocity; server software can require a newer Java version.
- Real Velocity 3.4.0 build 566 on Java 21: startup, controlled HTTP VPN detection, pre-login rejection, SQLite cache reuse, provider HTTP-503 recovery, negative verdict passing the plugin, command output, reload and shutdown.
- Repeated builds compared by SHA-256. GitHub CI checks the archive independently.

Spigot and BungeeCord retain their existing APIs. This release does not claim a new Minecraft version range. Full backend joins, all server versions, LuckPerms/Floodgate integration and Redis runtime operation are not covered by this release's proxy fixture. Provider fixtures establish behavior for controlled responses, not real-world detection accuracy.

## 0.4.9

See the [previous release](https://github.com/gerolndnr/connection-guard/releases/tag/0.4.9) and its comparison with 0.4.8.
