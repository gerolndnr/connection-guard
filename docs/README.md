# Set up Connection Guard

> Development candidate: [provider resilience and migration](PROVIDER_RESILIENCE.md) describes 0.5.2-SNAPSHOT. Stable 0.5.0/0.5.1 setup instructions below retain their original defaults; new candidate installs use ENFORCE and disabled geo lookups. General sequential VPN failover defaults on for selected providers, with or without keys; disable provider.vpn-failover.enabled to restore parallel voting. Existing modes, keys and selections are preserved. Full comparative acceptance is pending.


[Connection Guard website](https://connectionguard.net) · [Download and docs](https://connectionguard.net/download).

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

[bStats platform statistics and opt-out](BSTATS.md) explains the separate reporting settings and the Velocity initialization correction after 0.5.0.

[Local policy replay and shadow comparison](POLICY_REPLAY.md) compare saved synthetic cases or existing final login facts with a complete candidate without activating it. These development features are not included in stable 0.5.1.

## Validation scope

[Release qualification](RELEASE_0_5_0.md) binds the final package to its checks and selected native platform builds. Earlier [identity/permission](PERMISSION_VALIDATION.md) and [native challenge](NATIVE_CHALLENGE.md) evidence remains tied to its recorded source and JAR. Verified temporary challenge grants, full replay/rollback and account-wide persistent budgets remain future work. A dashboard time-limited operator rule is not a verified challenge grant.

[Comparative benchmark suite](../benchmarks/README.md) provides reproducible controlled checks against exact competitor artifacts, visible qualification gaps and a measured improvement backlog. Controlled fixtures do not establish real-world VPN detection or false-positive rates.

## Help

Use [GitHub issues](https://github.com/gerolndnr/connection-guard/issues) or [Discord](https://discord.gg/8q4HFCh2RK). Include plugin, server/proxy and Java versions, reproduction steps and sanitized settings. Remove secrets and personal connection data.
