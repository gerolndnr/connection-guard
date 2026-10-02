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

These guides describe 0.4.10 and are checked against its source and configuration. The real runtime fixture covers Velocity 3.4.0 build 566 / Java 21: startup, controlled HTTP detection, pre-login rejection, SQLite caching, provider recovery and commands. It does not include a complete backend join.

Spigot and BungeeCord are built and packaged in CI; a complete live runtime matrix is pending. Redis runtime, LuckPerms/Floodgate integration, Folia and every Minecraft version are not established by that fixture. [Release validation](../CHANGELOG.md) records the scope.

## Help

Use [GitHub issues](https://github.com/gerolndnr/connection-guard/issues) or [Discord](https://discord.gg/GekQVPqsfS). Include plugin, server/proxy and Java versions, reproduction steps and sanitized settings. Remove secrets and personal connection data.
