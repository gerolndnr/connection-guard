# Connection Guard on Spigot

For a standalone Spigot or compatible server. For a proxy network, begin with the BungeeCord or Velocity guide and verify the real client IP.

## Install and configure

1. The adapter builds against the Spigot 1.8.8 API; Spigot/core bytecode targets Java 8. Follow your server software's Java requirements. Build targets do not establish every Minecraft version or Folia support.
2. [Download the latest combined JAR](https://github.com/gerolndnr/connection-guard/releases/latest). Back up existing settings, stop the test server, place the JAR in the **server's** `plugins/` directory and remove an older Connection Guard JAR there when upgrading.
3. Start the test server, confirm successful plugin initialization and inspect the generated `config.yml` in the plugin data directory before accepting live players.
4. [Review providers, quotas and terms](../PROVIDERS.md). The default geo provider's free IP-API endpoint is for non-commercial use. Select a suitable provider before connection tests.
5. [Apply the notification profile](../CONFIGURATION.md). The shipped config enables VPN and geo kicks and contains CN/RU in its geo blocklist. Provider/cache changes require a restart.

## Confirm the setup

Run `cg help` in the server console; in game use `/cg` and the [documented permissions](../../README.md#commands-and-permissions). Privately inspect an authorized test IP with `cg info <IP>`. Confirm the expected client IP, provider result and action for an ordinary connection and a controlled flagged case.

Staff need `connectionguard.notify.vpn` and `connectionguard.notify.geo`. With an empty country blocklist, no geo-match notification is expected. Other plugins can still reject connections. Deliberately enable your blocking policy after reviewing results. Messages are in the generated `translation/en.yml`.

Permission exemptions on Spigot use LuckPerms and also require the respective config switch. Verify the actual player identity and context. [Troubleshoot exceptions and flagged players](../TROUBLESHOOTING.md). If initialization fails, fix it before relying on checks; [provider failure behavior](../PROVIDER_FAILURES.md) explains missing verdicts.

## Validation scope

This guide is checked against 0.4.10 source/config. Spigot is built and packaged in CI; a complete live Spigot version matrix is pending. The release's real runtime fixture is on Velocity. [Release checks](../../CHANGELOG.md). Record the exact server/proxy and Java versions with your deployment and support reports.
