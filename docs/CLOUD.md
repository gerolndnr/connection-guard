# Connection Guard Cloud (optional dashboard)

Available in Connection Guard 0.5.0.

Connection Guard Cloud is a free, optional web dashboard at **https://app.connectionguard.net**. It shows what Connection Guard checked, who it refused and why, provider health and quota, and totals over 24 hours to 90 days.

The plugin works fully without it. No account is required to use Connection Guard.

## How it behaves

- **Logins never wait on the cloud.** The link runs on one background thread. If the cloud is unreachable, players join exactly as before. Entries are buffered (at most 5,000), and older ones are dropped with a counter.
- **On by default, off in one line.** Any of these turns it off completely, so no request goes to connectionguard.net:
  - `cloud.enabled: false` in `config.yml`
  - `/cg cloud disable` (remembered across restarts; `/cg cloud enable` undoes it)
  - environment variable `CONNECTIONGUARD_CLOUD=false` or JVM flag `-Dconnectionguard.cloud=false` (for hosts and CI)
- **Linking.** A fresh installation shows a framed console block with a bright title and setup URL through the platform's native console renderer. When the background API provides the server link, it shows that link on its own line (valid for 24 hours). An unlinked server shows its current link again after a restart or code change; a confirmed linked server has no setup prompt. `/cg cloud link` shows the current link on demand. About two seconds after joining, operators (Paper/Spigot/Folia) or staff with `connectionguard.command.cloud` (including proxies) receive a colored clickable setup hint **once per person per installation**, including across reloads and clean restarts. Only the proxy sends the in-game hint when backend forwarding is enabled. If registration is still in progress, a fresh installation points to the dashboard and `/cg cloud link`; existing identities await confirmed link state. No hint or player task is scheduled when Cloud is disabled. Notice suppression uses bounded UUID hashes in `cloud/staff-dashboard-notices-v1.json`, kept locally and never sent to Cloud. Console/join/command setup links carry `src=console|join|command` for the dashboard's existing setup-source attribution. Open the link, sign in with Discord, name your network and accept the data processing terms. The setup assistant helps choose VPN/country rules and asks you to join your server to see a check.
- **Fleets.** For networks or hosting panels, create a network token in the dashboard and set `cloud.network-token`. New servers then join the network without a link.

## What is sent

| State | Sent to `api.connectionguard.net` | Never sent |
| --- | --- | --- |
| Off | Nothing | — |
| On, not linked (default) | Anonymous install ID, platform and version, plugin and Java version, mode (OBSERVE/ENFORCE), totals per interval (checks, admitted, refused, VPN found, cache hits, latency p50/p95, counts per country and reason), provider health (attempts, answers, local daily usage; no keys) | Player IPs, UUIDs, names, API keys, webhook URLs, console commands |
| Linked, terms accepted | Additionally, one entry per check: time, IP, verified UUID, verdict and reason, what each provider said (country, ASN, ISP, risk), matched rules | API keys, player names, console commands |

The exact wire format is open source: `packages/protocol` in `gerolndnr/connection-guard-cloud`.

From 0.5.2, Cloud sync can also send bounded anonymous metadata for Connection Guard's own exceptions to error tracking and the linked dashboard, without exception messages or player data. `cloud.error-reports: false` disables it independently; all Cloud off switches disable it too. [Report fields, limits and privacy controls](PRIVACY.md).

- **Storage:** entries are kept 30 days, hourly totals 13 months, unlinked installs without contact are deleted after 30 days. Data is stored in the EU.
- **Roles:** the server operator is the controller for their players' data; Connection Guard processes it only to show the dashboard.

## Configuring the plugin from the dashboard

The 0.5.2 candidate allowlist additionally accepts explicit Boolean switches
`provider.vpn.blackbox.enabled`, `provider.vpn.ipcheck.enabled` and
`provider.vpn.zowi.enabled`. Missing local selections snapshot as `false` and
are never automatically activated on upgrade. Their health and event IDs are
`vpn-blackbox`, `vpn-ipcheck`, `vpn-zowi`. A positive `vpn-blackbox` source means
aggregate VPN/proxy/Tor/hosting/cloud membership and must be labelled that way
by the dashboard. The existing strict event schema and `VPN_FLAG` enum are
retained; no extra reason field or personal-data category is sent. Dashboard
allowlist/display deployment is required before merge and does not change
provider order automatically.
[Player-IP recipients and opt-in](PROVIDERS.md#new-keyless-recipients-in-the-052-candidate).

Most everyday settings can be changed in the dashboard under **Settings**, in plain language instead of YAML:

- protection mode (OBSERVE or ENFORCE)
- VPN providers, with their API keys and the number of votes needed
- country rules (off, block selected, allow only selected) and the country lookup service
- what happens on a hit: refuse, notify staff, post to a Discord webhook
- what happens when a lookup fails
- exemptions (player names or IPs)
- cache durations

How it works:

- **Dashboard values take priority over `config.yml`.** They are stored in `plugins/<plugin>/cloud/managed-config.json` (owner-readable only) and layered over `config.yml` in memory on every load and reload. **`config.yml` itself is never written**, and every setting you did not change in the dashboard still comes from it.
- **Same validation as `/cg reload`.** A change is applied without a restart through the normal reload. If the server rejects it (for example, more required votes than enabled providers), the previous settings stay active and the dashboard shows the reason.
- **API keys and webhook URLs** are sent once, encrypted at rest in the cloud until the server confirms them, and then deleted from the cloud. Afterwards the dashboard only shows whether a key is set and its last four characters.
- **Never configurable remotely:** console commands on a hit (`execute-command`), the cache type and Redis connection, identity and forwarding, lookup tuning, overload limits, integrations, local lists and custom providers. These stay in `config.yml` only.
- **Back to `config.yml`:** use "Reset to config.yml" in the dashboard, or `/cg cloud reset-settings` on the server (works even with the cloud off). `/cg cloud settings` lists which values come from the dashboard; `/cg doctor` shows the cloud state.

## What else the dashboard can do on your server

Besides settings, only a fixed set of actions is enforced by the plugin itself:

- add or remove an allow, deny or exempt access rule
- clear cache entries
- unlink

Anything outside this set is refused and reported as failed.

## Self-hosting

The backend is open source (AGPL-3.0). Point `cloud.endpoint` at your own deployment (HTTPS required; plain HTTP only for `localhost`).

## Commands and permission

`/cg cloud status | link | settings | reset-settings | enable | disable` needs `connectionguard.command.cloud`. `/cg doctor` includes the cloud state.

## Current development coverage

The integrated bridge currently matches the original protocol-1 settings: ProxyCheck, IP-API, IPHub and VPNAPI, country settings, basic webhook routing, exemptions, mode and cache duration. IPQualityScore, local/custom/addon sources and richer webhook options remain available through `config.yml`; the dashboard must gain compatible fields before it can manage them. Fractional risk values are retained by the native decision engine; the older Cloud event schema can represent only integer risk values.

This bridge does not yet report the newer `rule_expiry` capability, so a compatible dashboard must keep its time-limited rule controls unavailable for this build. Existing generic dashboard access rules are separate from the planned verified identity/challenge-specific temporary exemptions.

Versioned local qualification drivers and their limits are in `ci/fixtures/cloud/README.md`. Native Paper, Folia and Bungee tests exercise complete accepted/rejected configuration drafts, custom-language retention, reset, persisted switch-off, real synthetic logins and shutdown during a deliberately hanging Cloud request. The separate browser fixture uses the existing setup/settings UI and an immutable **3e8771ee8ba19de0d59ec7f8f20764b05082a2e7** Cloud source copy on loopback; its hard-coded protocol fixture label refers to that source. No production Cloud, Discord OAuth or real operator onboarding is claimed by these tests.

## Dashboard access rules and compatibility

IP/CIDR rules can be permanent or time limited. Version 0.5.0 reports `rule_expiry`: UTC deadlines persist locally, expired rules never permit access, and background sync removes expired records. An invalid or already expired deadline rejects the command. These are operator access rules; they are not automatic identity-bound challenge grants.

The current dashboard settings expose the original four VPN services and custom REST provider. Configure native IPQualityScore, precise source-risk policy and advanced rich-webhook fields locally using their guides; those controls are not yet exposed in the dashboard. Cloud protocol v1 displays legacy integer risk; local decisions, caches, explain output and rich webhooks retain exact decimal risk. Unsupported remote fields reject the whole draft instead of partially applying it.


## Connection Guard Intel — unreleased 0.5.2 candidate

Fresh installations select the built-in signed local Intel bundle before VPN APIs;
existing installations must opt in. Daily background HTTPS downloads contact
https://intel.connectionguard.net/ without transmitting player IPs, UUIDs or names.
All four lists activate together after ECDSA P-256 signature, size and SHA-256
verification. Missing/unlisted/stale data is UNKNOWN (default72h); VPN/TOR blocks,
HOSTING only enriches, and RELAY defaults to ALLOW with a separate VPN option.
Endpoint/key are bundled and cannot be managed remotely. See
[local setup, precedence, attribution and test boundaries](LOCAL_DATA.md#built-in-connection-guard-intel-052-candidate).
Cloud source `connectionguard-intel` uses the coordinated optional source fields
`types` and `data_as_of`; unknown values are omitted. This candidate awaits a
new full benchmark and is not included in stable0.5.1.
