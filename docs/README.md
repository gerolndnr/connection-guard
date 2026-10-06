# Set up Connection Guard

> Released in **0.6.0**: [provider resilience and migration](PROVIDER_RESILIENCE.md) documents the published defaults. New installations use **ENFORCE** and can deny VPN/proxy/Tor evidence and Blackbox aggregate listings immediately; Geo and ip-check.net start disabled. Existing mode, keys and provider choices remain. The maintainer approved publication before the full competitive benchmark, which remains pending; no comparative detection, false-positive or burst-coverage claim is made.


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
- [Understand Cloud data and anonymous error reporting](PRIVACY.md).

## New in 0.5.0

[Optional Cloud dashboard](CLOUD.md), [managed access rules](ACCESS_RULES.md), [source-specific rules](RICH_RULES.md), [local lists and MMDB](LOCAL_DATA.md), [provider operations](OPERATIONS.md), [IPQualityScore](IPQUALITYSCORE.md), [German and Spanish messages](LANGUAGES.md) and [rich webhooks](WEBHOOKS.md) are part of this release. Optional admission/provider/observer integrations and separate addons retain their documented selection and licensing requirements.

[bStats platform statistics and opt-out](BSTATS.md) explains the separate reporting settings and the Velocity initialization correction after 0.5.0.

[Local policy replay and shadow comparison](POLICY_REPLAY.md) compare saved synthetic cases or existing final login facts with a complete candidate without activating it. These features are included in 0.6.0; replay/shadow estimates are bounded by the recorded facts and never activate a policy.

[Local version activation and rollback](POLICY_VERSIONING.md) add separately authorized durable transitions and explicit local/dashboard ownership. These tools are included in 0.6.0; historical native evidence remains bound to its original artifact, and full competitive qualification of 0.6.0 remains pending.

## Validation scope

[0.6.0 release qualification](RELEASE_0_6_0.md) binds the final package to its checks and selected native platform builds. Earlier [identity/permission](PERMISSION_VALIDATION.md) and [native challenge](NATIVE_CHALLENGE.md) evidence remains tied to its recorded source and JAR. Verified temporary challenge grants and account-wide persistent budgets remain future work; policy replay/rollback are included with their documented boundaries. A dashboard time-limited operator rule is not a verified challenge grant.

[Comparative benchmark suite](../benchmarks/README.md) provides reproducible controlled checks against exact competitor artifacts, visible qualification gaps and a measured improvement backlog. Controlled fixtures do not establish real-world VPN detection or false-positive rates.

## Help

Use [GitHub issues](https://github.com/gerolndnr/connection-guard/issues) or [Discord](https://discord.gg/8q4HFCh2RK). Include plugin, server/proxy and Java versions, reproduction steps and sanitized settings. Remove secrets and personal connection data.
