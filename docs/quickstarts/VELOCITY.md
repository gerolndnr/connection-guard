# Connection Guard on Velocity

For a network, install on the Velocity proxy receiving player connections. Verify the real client IP. Checks do not replace forwarding and backend-access configuration.

## Install and configure

1. The adapter targets the Velocity 3.3 API and requires Java 17 or newer. Follow Velocity's own Java requirements. The controlled release fixture used Velocity 3.4.0 build 566 / Java 21.
2. [Download the latest combined JAR](https://github.com/gerolndnr/connection-guard/releases/latest). Back up existing settings, stop the test proxy, place the JAR in the **proxy's** `plugins/` directory and remove an older Connection Guard JAR there when upgrading.
3. Start the test proxy, confirm successful plugin initialization and inspect the generated `config.yml` in the plugin data directory before accepting live players.
4. [Review providers, quotas and terms](../PROVIDERS.md). The 0.6.0 geo service is Disabled. VPN failover selects IP-API last; its free endpoint is HTTP and for non-commercial use. Review each selected recipient before connection tests.
5. [Review observation mode](../CONFIGURATION.md). **New 0.6.0 installs use ENFORCE and may reject flagged connections immediately.** Geo lookups and ip-check.net are disabled; choose OBSERVE explicitly if you want to review first. Existing settings are retained. Cache connection changes require a restart; provider drafts are validated on reload.

## Confirm the setup

Run `cg help` in the proxy console; in game use `/cg` and the [documented permissions](../../README.md#commands-and-permissions). Privately inspect an authorized test IP with `cg info <IP>`. Confirm the expected client IP, provider result and action for an ordinary connection and a controlled flagged case.

Staff need `connectionguard.notify.vpn` and `connectionguard.notify.geo`. With an empty country blocklist, no geo-match notification is expected. Other plugins can still reject connections. Use `/cg doctor` to confirm the selected mode and recipients. Set OBSERVE before test connections if you want to suppress actions. Messages are in the generated `translation/en.yml`.

Configure notifications through the proxy permission system. Configured pre-login exemptions use LuckPerms; the respective config switch must also be enabled. A Connection Guard allow decision does not guarantee a backend join; verify the complete join separately. [Troubleshoot exceptions and flagged players](../TROUBLESHOOTING.md). If initialization fails, fix it before relying on checks; [provider failure behavior](../PROVIDER_FAILURES.md) explains missing verdicts.

## Validation scope

This guide describes the unreleased 0.6.0 candidate. See [0.6.0 qualification and release gate](../RELEASE_0_6_0.md) for current checks. Historical 0.5.0 native evidence covers Paper 1.21.11 build 132, Folia 1.21.11 build 14, BungeeCord build 2100 and Velocity 3.4.0 build 566 / Java 21. See [release qualification](../RELEASE_0_5_0.md) for tested flows and limits. Separate [permission/identity evidence](../PERMISSION_VALIDATION.md) names its original artifact and fixture. These tests use synthetic clients; they do not establish every Minecraft version, authenticated Java/Bedrock accounts or public-provider detection accuracy. Record exact platform and Java versions with support reports.
