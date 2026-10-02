# Connection Guard on Velocity

For a network, install on the Velocity proxy receiving player connections. Verify the real client IP. Checks do not replace forwarding and backend-access configuration.

## Install and configure

1. The adapter targets the Velocity 3.3 API and requires Java 17 or newer. Follow Velocity's own Java requirements. The controlled release fixture used Velocity 3.4.0 build 566 / Java 21.
2. [Download the latest combined JAR](https://github.com/gerolndnr/connection-guard/releases/latest). Back up existing settings, stop the test proxy, place the JAR in the **proxy's** `plugins/` directory and remove an older Connection Guard JAR there when upgrading.
3. Start the test proxy, confirm successful plugin initialization and inspect the generated `config.yml` in the plugin data directory before accepting live players.
4. [Review providers, quotas and terms](../PROVIDERS.md). The default geo provider's free IP-API endpoint is for non-commercial use. Select a suitable provider before connection tests.
5. [Apply the notification profile](../CONFIGURATION.md). The shipped config enables VPN and geo kicks and contains CN/RU in its geo blocklist. Provider/cache changes require a restart.

## Confirm the setup

Run `cg help` in the proxy console; in game use `/cg` and the [documented permissions](../../README.md#commands-and-permissions). Privately inspect an authorized test IP with `cg info <IP>`. Confirm the expected client IP, provider result and action for an ordinary connection and a controlled flagged case.

Staff need `connectionguard.notify.vpn` and `connectionguard.notify.geo`. With an empty country blocklist, no geo-match notification is expected. Other plugins can still reject connections. Deliberately enable your blocking policy after reviewing results. Messages are in the generated `translation/en.yml`.

Configure notifications through the proxy permission system. Configured pre-login exemptions use LuckPerms; the respective config switch must also be enabled. A Connection Guard allow decision does not guarantee a backend join; verify the complete join separately. [Troubleshoot exceptions and flagged players](../TROUBLESHOOTING.md). If initialization fails, fix it before relying on checks; [provider failure behavior](../PROVIDER_FAILURES.md) explains missing verdicts.

## Validation scope

This guide is checked against 0.4.10 source/config. Real checks on the stated stack cover startup, local HTTP detection, pre-login rejection, SQLite reuse, provider recovery and commands. Full backend joins, Redis, LuckPerms/Floodgate and every proxy version are outside that fixture. Tests do not establish public-provider accuracy. [Release checks](../../CHANGELOG.md). Record the exact server/proxy and Java versions with your deployment and support reports.
