# Connection Guard on BungeeCord

For a network, install at the proxy receiving player connections. Verify the real client IP rather than duplicating lookups on every backend.

## Install and configure

1. BungeeCord/core bytecode targets Java 8. Follow your proxy software's Java requirements; it may need newer Java.
2. [Download the latest combined JAR](https://github.com/gerolndnr/connection-guard/releases/latest). Back up existing settings, stop the test proxy, place the JAR in the **proxy's** `plugins/` directory and remove an older Connection Guard JAR there when upgrading.
3. Start the test proxy, confirm successful plugin initialization and inspect the generated `config.yml` in the plugin data directory before accepting live players.
4. [Review providers, quotas and terms](../PROVIDERS.md). The default geo provider's free IP-API endpoint is for non-commercial use. Select a suitable provider before connection tests.
5. [Apply the notification profile](../CONFIGURATION.md). The shipped config enables VPN and geo kicks and contains CN/RU in its geo blocklist. Provider/cache changes require a restart.

## Confirm the setup

Run `cg help` in the proxy console; in game use `/cg` and the [documented permissions](../../README.md#commands-and-permissions). Privately inspect an authorized test IP with `cg info <IP>`. Confirm the expected client IP, provider result and action for an ordinary connection and a controlled flagged case.

Staff need `connectionguard.notify.vpn` and `connectionguard.notify.geo`. With an empty country blocklist, no geo-match notification is expected. Other plugins can still reject connections. Deliberately enable your blocking policy after reviewing results. Messages are in the generated `translation/en.yml`.

Configure notifications in the proxy permission system. Permission exemptions use LuckPerms on the relevant platform plus the respective config switch. Confirm player identity and context. [Troubleshoot exceptions and flagged players](../TROUBLESHOOTING.md). If initialization fails, fix it before relying on checks; [provider failure behavior](../PROVIDER_FAILURES.md) explains missing verdicts.

## Validation scope

This guide describes published 0.4.11, based on unchanged 0.4.10 connection-check behavior. BungeeCord is built and packaged in CI; a live BungeeCord/Waterfall runtime matrix remains pending. The hotfix's Paper activation test does not qualify Bungee runtime behavior. [Newer native permission/identity evidence](../PERMISSION_VALIDATION.md) belongs to development for a future release, not the published JAR. Tests do not establish public-provider accuracy. [Release checks](../../CHANGELOG.md). Record exact platform and Java versions with deployments and support reports.
