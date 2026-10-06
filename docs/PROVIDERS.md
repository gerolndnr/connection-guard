# Free anti-VPN software: understand provider quotas

> Development candidate: [provider resilience and migration](PROVIDER_RESILIENCE.md) describes 0.5.2-SNAPSHOT. Stable 0.5.0/0.5.1 setup instructions below retain their original defaults; new candidate installs use ENFORCE and disabled geo lookups. General sequential VPN failover defaults on for selected providers, with or without keys; disable provider.vpn-failover.enabled to restore parallel voting. Existing modes, keys and selections are preserved. Full comparative acceptance is pending.


Website guide: [Block VPNs on a Minecraft server](https://connectionguard.net/guides/block-vpn-minecraft-server).

Connection Guard is MIT-licensed software. It uses external IP intelligence providers, each with its own accuracy, availability, terms and quotas. No Connection Guard account is required. A selected provider's API key is a separate credential.

Reviewed 2 October 2026. Check the linked official information before deployment; limits and terms can change.

| Provider | Integration | Official information |
| --- | --- | --- |
| ProxyCheck | VPN/proxy and geo; default VPN provider | [Pricing](https://proxycheck.io/pricing/) and [API documentation](https://proxycheck.io/api/) |
| IP-API | VPN/proxy and geo; default geo provider | [JSON documentation](https://ip-api.com/docs/api:json) |
| IPHub | VPN/proxy; API key configured in the plugin | [API documentation](https://iphub.info/api) |
| VPNAPI | VPN/proxy; API key configured in the plugin | [Official site](https://vpnapi.io/) |
| IPQualityScore | Optional native adapter in unreleased 0.5.0-SNAPSHOT; [configuration and exact risk](IPQUALITYSCORE.md) | [API](https://www.ipqualityscore.com/documentation/proxy-detection-api/overview) and [terms](https://www.ipqualityscore.com/terms-of-service) |
| Custom provider | Configurable GET/POST API and response fields | Your provider's official documentation |

ProxyCheck currently advertises **1,000 daily queries** for its registered free plan. This is a query allowance, not a count of unique Minecraft players. Check your account's actual allowance.

The free IP-API endpoint allows **45 requests per minute per source IP**, uses HTTP and **does not allow commercial use**. Persistent overuse can trigger a temporary ban. Choose a suitable supported provider for your use; Connection Guard does not automatically purchase a plan.

## How requests accumulate

### New keyless recipients in the 0.5.2 candidate

**Unreleased, pending comparative benchmark acceptance.** Only new configuration
files select local Tor → signed Connection Guard Intel → ProxyCheck → Blackbox → ip-check.net → zowi → IPQuery →
IP-API. Existing files keep their providers and order and get a once-only
recommendation. The local Tor lookup sends no player IP; each reached external
service receives the queried player's IP. A successful positive or negative
answer stops the chain. On errors more than one service may receive that IP,
sequentially, within the unchanged 5,000 ms deadline and attempt limit.

| Config ID / Cloud ID | Recipient and evidence | Operator and published information |
| --- | --- | --- |
| `blackbox` / `vpn-blackbox` | `https://blackbox.ipinfo.app/api/v1/<IP>`; Y is listed, N unlisted. **Y includes hosting/cloud lists and can deny them.** Specific VPN/hosting flags are not inferred. | Cameron Munroe / ipinfo.app, named in the [privacy policy](https://ipinfo.app/privacy/). [Documentation](https://blackbox.ipinfo.app/) describes free unlimited v1 use; **no written terms** are published on the reviewed pages. |
| `ipcheck` / `vpn-ipcheck` | `https://ip-check.net/api/proxy-detect.php?ip=<encoded-IP>`; TRUE/FALSE. | **ip-check.net publishes neither an operator nor terms nor a privacy policy**, on the [reviewed site](https://ip-check.net/), checked 2026-10-06. Do not infer data-handling guarantees. |
| `zowi` / `vpn-zowi` | `https://api.zowi.gay/<IP>`; explicit VPN/proxy/Tor flags. Hosting-only stays review and continues failover. | **Zowi, developer of competing FoxGate**; see the [first-party service documentation](https://github.com/IDCTeam-Group/FoxGate-Issues/wiki/Services). No separate processing agreement is asserted here. |

The three adapters default to **60 locally counted requests/minute each**. This
conservative plugin cap is not an upstream rate-limit or availability promise:
Blackbox advertises unlimited v1 use, and FoxGate's service guide mentions a
higher keyless cap for zowi. No account, key, purchase or circumvention is added.
429/Retry-After and broken responses keep the result UNKNOWN, never clean.

Before explicitly enabling a recipient on an existing installation, include it
in your server's own player privacy information and assess its published terms
and data handling. Add the appropriate `provider.vpn.<id>.enabled: true` sections
(and optionally `minute-budget: 60`), retain the services you want, then set
`provider.vpn-failover.order: [proxycheck, blackbox, ipcheck, zowi, ipquery, ip-api]`
using only IDs present in your file. `/cg reload` validates the complete draft.
Use `enabled: false` to exclude a service; an order entry never enables it.
Cloud's off switch controls Cloud, not these detection services.
[Full behavior and migration](PROVIDER_RESILIENCE.md).

VPN and geo lookups are separate. Multiple enabled VPN providers can each receive a request for an uncached IP. Expiration, restarts, failures and cache clearing affect volume. Repeated connections may use cached data.

Illustrative quota planning, **not a measured workload**: 100 uncached IPs with one VPN and one geo lookup can produce 200 requests. If both use the same provider account, they can draw from the same allowance. Check provider-specific counting rules.

SQLite is the simple initial cache. Shipped expirations are 1,440 minutes for VPN and 4,320 minutes for geo. Results can become stale after an IP changes use. Choose expiration for your policy; frequent clearing increases requests.

## What happens during an outage?

If the positive VPN vote threshold is not met, the connection proceeds, subject to geo rules and other plugins. A missing answer is not evidence that an IP is safe. Incomplete negative verdicts are not cached; a later connection can retry. An unavailable geo response provides no geo verdict. [Decision and caching table](PROVIDER_FAILURES.md).

If the configured cache cannot initialize, the plugin stops initialization. This does not stop the whole server or create a fail-closed connection policy. Check startup logs before relying on checks.

## Choose deliberately

Start with a provider whose terms permit your use and confirm its allowance. Add providers with a reason and enough quota, then select a meaningful vote threshold. Threshold changes are not guaranteed accuracy improvements.

Detection requests share IP addresses with selected providers. Consult their official privacy information when documenting your setup. Keep keys private. In 0.4.10, free IP-API uses HTTP; ProxyCheck and IPHub requests use HTTPS.


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
