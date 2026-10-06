<p align="center"><img src="docs/connection-guard-logo.png" alt="Connection Guard" width="360"></p>

# Connection Guard

### Connection rules. Your control.

Free, open-source **VPN/proxy checks and country rules** for Spigot, BungeeCord and Velocity. Choose your detection providers and what happens when a rule matches.

[Website](https://connectionguard.net) · [Download](https://connectionguard.net/download) · [Set up your server](docs/README.md) · [Get help](https://github.com/gerolndnr/connection-guard/issues) · [Discord](https://discord.gg/8q4HFCh2RK)

**Stable build: 0.5.1.**

**Latest stable release: [0.5.1](https://github.com/gerolndnr/connection-guard/releases/tag/0.5.1).** [Changes and upgrade guide](CHANGELOG.md).

**MIT licensed.** No Connection Guard account or GitHub star is required. An optional free dashboard is available, on by default and off with one setting; see [docs/CLOUD.md](docs/CLOUD.md). External detection providers have their own quotas and usage terms; free software does not imply unlimited free lookups.

## What you can control

- **VPN/proxy checks:** select supported providers or configure a custom REST API provider.
- **Actions:** reject a flagged connection, notify staff, execute a configured console command or send a Discord webhook.
- **Country rules:** choose an allowlist or blocklist and the response to a match.
- **Multiple-provider voting:** configure how many enabled providers must return a positive VPN/proxy result.
- **Caching:** reuse lookup results to reduce repeated provider requests.
- **Access policy:** scoped IPv4/IPv6 CIDR, trusted UUID and time-limited rules; optional ASN, ISP, country and source-specific risk selectors. [Rules](docs/ACCESS_RULES.md).
- **Local data:** attributed address lists and optional Geo/ASN MMDB files, with freshness checks. [Local sources](docs/LOCAL_DATA.md).
- **Provider control:** native IPQualityScore, local request budgets, bounded timeouts and explicit UNKNOWN behavior. [Provider operations](docs/OPERATIONS.md).
- **Diagnosis:** `/cg doctor`, `/cg providers`, `/cg stats` and `/cg explain <IP>` explain configuration, source health and decisions.
- **Optional cloud:** see checks and manage supported settings at [app.connectionguard.net](https://app.connectionguard.net), with background sync and an explicit off switch.
- **Messages:** English, German and Spanish, plus private-by-default rich decision webhooks. [Languages](docs/LANGUAGES.md) · [Webhooks](docs/WEBHOOKS.md).

From 0.5.2, enabled Cloud sync also carries bounded anonymous metadata for Connection Guard's own exceptions, without exception messages or player data. `cloud.error-reports: false` disables these reports; every Cloud off switch does too. [Data and controls](docs/PRIVACY.md).

## Start here

| Your setup | Guide |
| --- | --- |
| Standalone Spigot or compatible server | [Spigot quickstart](docs/quickstarts/SPIGOT.md) · [Anti-VPN for Paper and Spigot](https://connectionguard.net/paper-anti-vpn) |
| BungeeCord proxy network | [BungeeCord quickstart](docs/quickstarts/BUNGEECORD.md) · [Anti-VPN for BungeeCord](https://connectionguard.net/bungeecord-anti-vpn) |
| Velocity proxy network | [Velocity quickstart](docs/quickstarts/VELOCITY.md) · [Anti-VPN for Velocity](https://connectionguard.net/velocity-anti-vpn) |

The combined JAR is available from [GitHub](https://github.com/gerolndnr/connection-guard/releases/latest), [Spigot](https://www.spigotmc.org/resources/121509/), [Modrinth](https://modrinth.com/plugin/connectionguard) and [Hangar](https://hangar.papermc.io/gerolndnr/connection-guard). To build it yourself, follow [CONTRIBUTING.md](CONTRIBUTING.md).

Review the generated configuration before accepting live players. **New 0.5.0 installations start in OBSERVE with an empty country blocklist.** Inspect decisions, then deliberately enable ENFORCE. Existing configurations without `operation.mode` keep their previous ENFORCE behavior. [Choose your policy](docs/CONFIGURATION.md).

The default VPN provider is ProxyCheck; the default geo provider is IP-API. The free IP-API endpoint is for non-commercial use, is rate-limited and uses HTTP. [Choose providers and understand quotas](docs/PROVIDERS.md) before deployment. Website guides: [Block VPNs on a Minecraft server](https://connectionguard.net/guides/block-vpn-minecraft-server) · [Block countries on a Minecraft server](https://connectionguard.net/guides/block-countries-minecraft-server).

## Commands and permissions

Use `/cg` or `/connectionguard` in game, and omit `/` in the console.

| Command | Purpose | Permission |
| --- | --- | --- |
| `/cg help` | Show available commands | `connectionguard.command.help` |
| `/cg cloud sync` | Request immediate background Cloud sync (from 0.5.2) | `connectionguard.command.cloud` |
| `/cg info <IP>` | Inspect provider-supplied information | `connectionguard.command.info` |
| `/cg reload` | Validate and reload settings/messages; cache connection changes require a restart | `connectionguard.command.reload` |
| `/cg clear <IP>` | Clear VPN and geo cache entries for an IP | `connectionguard.command.clear` |
| `/cg clear` | Clear the entire cache, temporarily increasing provider requests | `connectionguard.command.clear` |

Targeted `info` and `clear` also accept an online player name or UUID. Staff notification permissions are `connectionguard.notify.vpn` and `connectionguard.notify.geo`. [Troubleshoot flagged players and exemptions](docs/TROUBLESHOOTING.md).

## Reliability and compatibility

The 0.5.1 maintenance release fixes Velocity bStats and adds reproducible developer benchmark checks. [0.5.1 checks and limits](docs/RELEASE_0_5_1.md).

The 0.5.0 release combines the tested rule, provider, identity, scheduler and cloud work since 0.4.11. Its exact combined JAR is checked through reproducible builds, packaging guards and controlled native startup/login/reload/shutdown fixtures. [Release checks and limits](docs/RELEASE_0_5_0.md).

Core/Spigot/BungeeCord bytecode targets Java 8. The Spigot adapter builds against the 1.8.8 API. The Velocity adapter targets the 3.3 API and requires Java 17 or newer. Follow your server software's Java requirements. Build targets do not prove every server version was tested; additional startup, command, reload and shutdown checks passed on Paper 26.3-151, Folia 26.2-7, Velocity 4.2.0-30 and 4.2.1-SNAPSHOT-36 with Java 25. Selected Paper 1.21.11, Folia 1.21.11, Bungee build 2100 and Velocity 3.4.0 fixtures have separate [named platform/identity evidence](docs/PERMISSION_VALIDATION.md) and [native challenge/backend evidence](docs/NATIVE_CHALLENGE.md).

Incomplete lookups are UNKNOWN; your scoped OPEN/OBSERVE/CLOSED failure policy decides how they affect access. Global OBSERVE suppresses enforcement. Explicit rules, geo policy and other plugins also apply. See [provider failures and caching](docs/PROVIDER_FAILURES.md). Tests do not establish real-world detection accuracy. Connection Guard does not replace an anticheat, a complete antibot system or network-level DDoS protection.

## Help and contribution

[Open an issue](https://github.com/gerolndnr/connection-guard/issues) or use [project Discord](https://discord.gg/8q4HFCh2RK). Share plugin, platform and Java versions, reproduction steps and sanitized settings. Remove keys, webhook URLs and personal connection data.

Contributions and documentation improvements are welcome. [Development guide](CONTRIBUTING.md) · [MIT license](LICENSE). Stars and honest reviews are optional. The separately built, optional [LibertyBans addon](adapters/libertybans/README.md) and its native fixture are licensed under AGPL-3.0-or-later, as specified in their directories; they are excluded from the MIT combined plugin.

## Credits

Connection Guard uses [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio), [Kotlin](https://github.com/JetBrains/kotlin), [Gson](https://github.com/google/gson), [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) and [Jedis](https://github.com/redis/jedis). License notices for bundled dependencies are included in the release JAR. The original README was adapted from [electron-markdownify](https://github.com/amitmerchant1990/electron-markdownify).

[LNDNR's Anti-VPN & Geo-Blocking](https://www.spigotmc.org/resources/116744/) is the predecessor. Current documentation and downloads are linked above.
