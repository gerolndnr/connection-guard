<p align="center"><img src="docs/connection-guard-logo.png" alt="Connection Guard" width="240"></p>

# Connection Guard

### Connection rules. Your control.

Free, open-source **VPN/proxy checks and country rules** for Spigot, BungeeCord and Velocity. Choose your detection providers and what happens when a rule matches.

[Download](https://github.com/gerolndnr/connection-guard/releases/latest) · [Set up your server](docs/README.md) · [Get help](https://github.com/gerolndnr/connection-guard/issues) · [Discord](https://discord.gg/GekQVPqsfS)

**MIT licensed.** No Connection Guard account or GitHub star is required. External detection providers have their own quotas and usage terms; free software does not imply unlimited free lookups.

## What you can control

- **VPN/proxy checks:** select supported providers or configure a custom REST API provider.
- **Actions:** reject a flagged connection, notify staff, execute a configured console command or send a Discord webhook.
- **Country rules:** choose an allowlist or blocklist and the response to a match.
- **Multiple-provider voting:** configure how many enabled providers must return a positive VPN/proxy result.
- **Caching:** reuse lookup results to reduce repeated provider requests.
- **Connection details:** inspect the information returned by your providers with `/cg info <IP>`.

## Start here

| Your setup | Guide |
| --- | --- |
| Standalone Spigot or compatible server | [Spigot quickstart](docs/quickstarts/SPIGOT.md) |
| BungeeCord proxy network | [BungeeCord quickstart](docs/quickstarts/BUNGEECORD.md) |
| Velocity proxy network | [Velocity quickstart](docs/quickstarts/VELOCITY.md) |

The combined JAR is available from [GitHub](https://github.com/gerolndnr/connection-guard/releases/latest), [Spigot](https://www.spigotmc.org/resources/121509/), [Modrinth](https://modrinth.com/plugin/connectionguard) and [Hangar](https://hangar.papermc.io/gerolndnr/connection-guard). To build it yourself, follow [CONTRIBUTING.md](CONTRIBUTING.md).

Review the generated configuration before accepting live players. **The shipped 0.4.10 config enables VPN and geo kicks, with CN/RU in its country blocklist.** [Start with notifications](docs/CONFIGURATION.md) to evaluate decisions before blocking.

The default VPN provider is ProxyCheck; the default geo provider is IP-API. The free IP-API endpoint is for non-commercial use, is rate-limited and uses HTTP. [Choose providers and understand quotas](docs/PROVIDERS.md) before deployment.

## Commands and permissions

Use `/cg` or `/connectionguard` in game, and omit `/` in the console.

| Command | Purpose | Permission |
| --- | --- | --- |
| `/cg help` | Show available commands | `connectionguard.command.help` |
| `/cg info <IP>` | Inspect provider-supplied information | `connectionguard.command.info` |
| `/cg reload` | Reload settings/messages; provider/cache changes require a restart | `connectionguard.command.reload` |
| `/cg clear <IP>` | Clear VPN and geo cache entries for an IP | `connectionguard.command.clear` |
| `/cg clear` | Clear the entire cache, temporarily increasing provider requests | `connectionguard.command.clear` |

Targeted `info` and `clear` also accept an online player name or UUID. Staff notification permissions are `connectionguard.notify.vpn` and `connectionguard.notify.geo`. [Troubleshoot flagged players and exemptions](docs/TROUBLESHOOTING.md).

## Reliability and compatibility

0.4.10 includes 48 passing regression tests and a controlled runtime check on **Velocity 3.4.0 / Java 21** covering startup, HTTP detection, pre-login rejection, SQLite caching, provider recovery and commands. [Read the checks and limits](CHANGELOG.md).

Core/Spigot/BungeeCord bytecode targets Java 8. The Spigot adapter builds against the 1.8.8 API. The Velocity adapter targets the 3.3 API and requires Java 17 or newer. Follow your server software's Java requirements. Build targets do not prove every server version was tested; Folia support is unverified.

If the configured VPN vote threshold is not met, the connection proceeds, subject to geo rules and other plugins. An unavailable geo lookup provides no geo verdict. See [provider failures and caching](docs/PROVIDER_FAILURES.md). Tests do not establish real-world detection accuracy. Connection Guard does not replace an anticheat, a complete antibot system or network-level DDoS protection.

## Help and contribution

[Open an issue](https://github.com/gerolndnr/connection-guard/issues) or use [project Discord](https://discord.gg/GekQVPqsfS). Share plugin, platform and Java versions, reproduction steps and sanitized settings. Remove keys, webhook URLs and personal connection data.

Contributions and documentation improvements are welcome. [Development guide](CONTRIBUTING.md) · [MIT license](LICENSE). Stars and honest reviews are optional. The separately built, optional [LibertyBans addon](adapters/libertybans/README.md) and its native fixture are licensed under AGPL-3.0-or-later, as specified in their directories; they are excluded from the MIT combined plugin.

## Credits

Connection Guard uses [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio), [Kotlin](https://github.com/JetBrains/kotlin), [Gson](https://github.com/google/gson), [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) and [Jedis](https://github.com/redis/jedis). License notices for bundled dependencies are included in the release JAR. The original README was adapted from [electron-markdownify](https://github.com/amitmerchant1990/electron-markdownify).

[LNDNR's Anti-VPN & Geo-Blocking](https://www.spigotmc.org/resources/116744/) is the predecessor. Current documentation and downloads are linked above.
