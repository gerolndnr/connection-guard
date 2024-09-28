
<h1 align="center">
  <br>
  <a href="https://github.com/gerolndnr/connection-guard"><img src="https://raw.githubusercontent.com/gerolndnr/connection-guard/master/docs/connection-guard-logo.png" alt="Connection Guard" width="300"></a>
  <br>
  Connection Guard
  <br>
</h1>

<h4 align="center">A feature-rich vpn and geo-blocker for Spigot, BungeeCord and Velocity</h4>

<p align="center">
  <a href="#key-features">Key Features</a> •
  <a href="#how-to-use">How To Use</a> •
  <a href="#credits">Credits</a> •
  <a href="#related">Related</a> •
  <a href="#license">License</a>
</p>

## Key Features

* **Detect VPNs** - react to them how you like!
  * Kick: Prevent players with VPNs to join your server.
  * Notify: Notify admins or moderators that a VPN user joined.
  * Command: Execute a command when a VPN user joins.
* **Multiple Detection Provider** - Minimize false flags, maximize detection!
  * ProxyCheck (100 queries per day for free without api key, 1.000 queries per day for free with api key)
  * IP-Hub (1.000 queries per day for free with api key)
  * IP-API (45 queries per minute for free, no api key required)
  * VPN-API (1.000 queries per day for free with api key)
  * Custom Provider (configure the plugin to automatically use the REST-API of the detection provider, supports `GET` and `POST`)
* **Geo-Blocking** - No more bots from foreign countries!
  * `Whitelist` or `Blacklist` mode
  * Over 200 countries supported!
* **Connection Information** - Know everything about your players connection!
  * IP address (and whether it is a vpn/proxy)
  * Country code (e.g. `US`, `CA`, ...)
  * City name (e.g. `Berlin`, `London`, ...)
  * ISP provider (e.g. `AT&T`, `Telekom`, ...)

## How To Use

### Installation

To use Connection Guard, you need a **Spigot server** (or Paper, Pufferfish, Purpur, ...) running on `1.8.X` and `1.21.X` or an up-to-date version of **BungeeCord** (or Waterfall) or **Velocity**.

1. Build the project (`./gradlew clean shadowJar`) or [download it](https://github.com/gerolndnr/connection-guard/releases) from the release section.
2. Place the downloaded `.jar` file into the plugins folder of your Spigot, BungeeCord or Velocity server.
3. Start or restart your server.
4. Optional: Configure Connection Guard configuration in its directory (`config.yml` and `translation/en.yml`)

### Usage
When freshly installed, Connection Guard blocks VPN connections and notifies all players with the `connectionguard.notify.vpn` permission (`KICK_NOTIFY`).
All players are geo checked by default, but when players from Russia or China join, all players with the 
`connectionguard.notify.geo` permission are notified (`NOTIFY`). You can customize every aspect including all
messages sent to players in the `config.yml` and the corresponding messages file (`en.yml` by default.)
 - `/connectionguard help` Help overview of Connection Guard commands
   - Permission: `connectionguard.command.help`
 - `/connectionguard reload` Reload the config and the messages file. Changes to providers require a restart.
   - Permission: `connectionguard.command.reload`
 - `/connectionguard clear (<Player/UUID/IP>)` Clear the entire cache or just for the specified player/uuid/ip. If you specify a player or an uuid, the player has to be online.
   - Permission: `connectionguard.command.clear`
 - `/connectionguard info <Player/UUID/IP>` Show all connection information (IP, VPN, Country, City, ISP) about the player or the IP. If you specify a player or an uuid, the player has to be online.
   - Permission: `connectionguard.command.info`

## Credits

This software uses the following open source packages:

- [OkHttp](https://github.com/square/okhttp)
- [Gson](https://github.com/google/gson)
- [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)
- [Jedis](https://github.com/redis/jedis)
- Readme is taken from [here](https://github.com/amitmerchant1990/electron-markdownify)

## Related

[LNDNR's Anti-VPN & Geo-Blocking](https://www.spigotmc.org/resources/lndnrs-anti-vpn-geo-blocking-1-16-5-1-21-x-bedrock-support.116744/) - Predecessor of Connection Guard

## Help

- [Discord Server](https://discord.gg/GekQVPqsfS) or contact me on discord directly: `gold.ly`

## License

MIT

---

> GitHub [@gerolndnr](https://github.com/gerolndnr)


