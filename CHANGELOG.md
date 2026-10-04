# Changelog

## Unreleased

### Connection Guard Cloud (optional dashboard)

- New: optional free dashboard at app.connectionguard.net. It is **on by default** and sends only anonymous totals until you link the server; turn it off with `cloud.enabled: false`, `/cg cloud disable` or `CONNECTIONGUARD_CLOUD=false`. Details and the full list of transmitted data: `docs/CLOUD.md`.
- New: configure the plugin from the dashboard (mode, providers and API keys, country rules, actions on a hit, failure policy, exemptions, cache). Dashboard values are layered over `config.yml` in memory and never written to it. They are validated like `/cg reload`, and rejected changes keep the previous settings. Console commands, cache connection, identity and lookup tuning stay `config.yml`-only.
- New: `/cg cloud status|link|settings|reset-settings|enable|disable` (permission `connectionguard.command.cloud`); `/cg doctor` shows the cloud state.
- Logins never wait on the cloud; failures only reschedule the background sync.
- Velocity now reports usage through the cloud link (it never sent bStats data).

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
