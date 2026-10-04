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

## New in 0.5.0

[Optional Cloud dashboard](CLOUD.md), [managed access rules](ACCESS_RULES.md), [source-specific rules](RICH_RULES.md), [local lists and MMDB](LOCAL_DATA.md), [provider operations](OPERATIONS.md), [IPQualityScore](IPQUALITYSCORE.md), [German and Spanish messages](LANGUAGES.md) and [rich webhooks](WEBHOOKS.md) are part of this release. Optional admission/provider/observer integrations and separate addons retain their documented selection and licensing requirements.

## Validation scope

[Release qualification](RELEASE_0_5_0.md) binds the final package to its checks and selected native platform builds. Earlier [identity/permission](PERMISSION_VALIDATION.md) and [native challenge](NATIVE_CHALLENGE.md) evidence remains tied to its recorded source and JAR. Verified temporary challenge grants, full replay/rollback and account-wide persistent budgets remain future work. A dashboard time-limited operator rule is not a verified challenge grant.

## Help

Use [GitHub issues](https://github.com/gerolndnr/connection-guard/issues) or [Discord](https://discord.gg/8q4HFCh2RK). Include plugin, server/proxy and Java versions, reproduction steps and sanitized settings. Remove secrets and personal connection data.
