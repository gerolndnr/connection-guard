# Set up Connection Guard

Choose the software that receives the player's connection:

| Setup | Start here |
| --- | --- |
| Standalone Spigot or compatible server | [Spigot quickstart](quickstarts/SPIGOT.md) |
| BungeeCord network | [BungeeCord quickstart](quickstarts/BUNGEECORD.md) |
| Velocity network | [Velocity quickstart](quickstarts/VELOCITY.md) |

For a proxy network, begin at the proxy and verify that it sees the actual client IP. Installing checks on every backend can produce duplicate lookups or checks against a proxy address. Review forwarding and backend access instead of trusting an arbitrary forwarded IP.

After installation:

- [Evaluate notifications and country rules](CONFIGURATION.md).
- [Choose providers and understand free quotas](PROVIDERS.md).
- [Investigate flagged players and permission exemptions](TROUBLESHOOTING.md).
- [Understand provider outages and caching](PROVIDER_FAILURES.md).

## Validation scope

The quickstart steps describe the published 0.4.11 hotfix and its unchanged 0.4.10 connection-check behavior. The 0.4.10 fixture used Velocity 3.4.0 build 566 / Java 21 for startup, controlled HTTP detection, pre-login rejection, SQLite caching, provider recovery and commands. It did not include a complete backend join. The 0.4.11 hotfix separately passed controlled Paper 1.21.11 build 132 / Java 21 activation, reload, an offline synthetic backend join and shutdown. Neither release fixture establishes every server version or LuckPerms/Floodgate scenario.

Development work on `master` has separate [permission/identity evidence](PERMISSION_VALIDATION.md), [native identity rules](NATIVE_IDENTITY.md) and [operating controls](OPERATIONS.md), [read-only admission API](ADMISSION_API.md) and the separate [LibertyBans addon](../adapters/libertybans/README.md). These newer features and native Paper/Folia/Velocity results are for a future feature release; they do not describe the downloadable 0.4.11 JAR. Selected [native Bungee/Velocity ban evidence](../ci/fixtures/native-libertybans-proxies/README.md) is also available. A separate default-disabled [native challenge addon](NATIVE_CHALLENGE.md) has controlled map/chat and missing-SDK evidence; verified temporary grants remain unfinished. Full Bungee/legacy Spigot version coverage and authenticated Java/Bedrock coverage remain incomplete. [Release notes](../CHANGELOG.md) record published behavior.

## Help

Use [GitHub issues](https://github.com/gerolndnr/connection-guard/issues) or [Discord](https://discord.gg/GekQVPqsfS). Include plugin, server/proxy and Java versions, reproduction steps and sanitized settings. Remove secrets and personal connection data.
