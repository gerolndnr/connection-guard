# Changelog

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
