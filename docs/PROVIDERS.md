# Free anti-VPN software: understand provider quotas

> Released in **0.6.0**: [provider resilience and migration](PROVIDER_RESILIENCE.md) documents the published defaults. New installations use **ENFORCE** and can deny VPN/proxy/Tor evidence and Blackbox aggregate listings immediately; Geo and ip-check.net start disabled. Existing mode, keys and provider choices remain. The maintainer approved publication before the full competitive benchmark, which remains pending; no comparative detection, false-positive or burst-coverage claim is made.


Website guide: [Block VPNs on a Minecraft server](https://connectionguard.net/guides/block-vpn-minecraft-server).

Connection Guard is MIT-licensed software. It uses external IP intelligence providers, each with its own accuracy, availability, terms and quotas. No Connection Guard account is required. A selected provider's API key is a separate credential.

Reviewed 2 October 2026. Check the linked official information before deployment; limits and terms can change.

| Provider | Integration | Official information |
| --- | --- | --- |
| ProxyCheck | VPN/proxy and geo; default VPN provider | [Pricing](https://proxycheck.io/pricing/) and [API documentation](https://proxycheck.io/api/) |
| IP-API | VPN/proxy and geo; final VPN fallback, geo requires opt-in | [JSON documentation](https://ip-api.com/docs/api:json) |
| IPHub | VPN/proxy; API key configured in the plugin | [API documentation](https://iphub.info/api) |
| VPNAPI | VPN/proxy; API key configured in the plugin | [Official site](https://vpnapi.io/) |
| IPQualityScore | Optional native adapter; [configuration and exact risk](IPQUALITYSCORE.md) | [API](https://www.ipqualityscore.com/documentation/proxy-detection-api/overview) and [terms](https://www.ipqualityscore.com/terms-of-service) |
| Custom provider | Configurable GET/POST API and response fields; see [Custom providers](#custom-providers) | Your provider's official documentation |

ProxyCheck currently advertises **1,000 daily queries** for its registered free plan. This is a query allowance, not a count of unique Minecraft players. Check your account's actual allowance.

The free IP-API endpoint allows **45 requests per minute per source IP**, uses HTTP and **does not allow commercial use**. Persistent overuse can trigger a temporary ban. Choose a suitable supported provider for your use; Connection Guard does not automatically purchase a plan.

## How requests accumulate

### New keyless recipients in 0.6.0

**Unreleased, pending comparative benchmark acceptance.** Only new configuration
files select local Tor → signed Connection Guard Intel → ProxyCheck → Blackbox → zowi → IPQuery → IP-API. ip-check.net is **disabled by default**; explicit opt-in places it between Blackbox and zowi. Existing files keep their providers and order and get a once-only
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

In 0.6.0, an unavailable Redis cache starts with a bounded Memory fallback and reconnects in the background. Other invalid cache configurations can still prevent initialization. Check startup logs and `/cg doctor` before relying on checks.

## Choose deliberately

Start with a provider whose terms permit your use and confirm its allowance. Add providers with a reason and enough quota, then select a meaningful vote threshold. Threshold changes are not guaranteed accuracy improvements.

Detection requests share IP addresses with selected providers. Consult their official privacy information when documenting your setup. Keep keys private. In 0.4.10, free IP-API uses HTTP; ProxyCheck and IPHub requests use HTTPS.


## Connection Guard Intel — 0.6.0

Fresh installations select the built-in signed local Intel bundle before VPN APIs;
existing installations must opt in. Daily background HTTPS downloads contact
https://intel.connectionguard.net/ without transmitting player IPs, UUIDs or names.
All four lists activate together after ECDSA P-256 signature, size and SHA-256
verification. Missing/unlisted/stale data is UNKNOWN (default72h); VPN/TOR blocks,
HOSTING only enriches, and RELAY defaults to ALLOW with a separate VPN option.
Endpoint/key are bundled and cannot be managed remotely. See
[local setup, precedence, attribution and test boundaries](LOCAL_DATA.md#built-in-connection-guard-intel-060).
Cloud source `connectionguard-intel` uses the coordinated optional source fields
`types` and `data_as_of`; unknown values are omitted. These features are included in 0.6.0.
The full competitive benchmark of the published artifact remains pending.
## Custom providers

Any detection service with a JSON HTTP API can be added without code. Every section under `provider.vpn` whose name is not a built-in provider (`proxycheck`, `ip-api`, `iphub`, `vpnapi`, `ipqualityscore`, and from 0.6.0 `ipquery`, `blackbox`, `ipcheck`, `zowi`) or `local` is read as a custom provider. The shipped file contains a disabled example called `custom`. Use one section per service, with any name you like.

```yaml
provider:
  vpn:
    myprovider:                      # any name that is not a built-in provider
      enabled: true
      request-type: 'GET'            # GET or POST
      # %IP% is replaced by the player's address. http and https URLs are accepted; use https.
      request-url: 'https://api.example.com/v1/check/%IP%'
      # Prefer a header for keys: the URL can end up in proxy or provider logs.
      request-header:
        - "Authorization: Bearer YOUR_API_KEY"
      # Only used when request-type is POST. %IP% works here too.
      request-body-type: 'application/json'
      request-body: '{ "ip": "%IP%" }'
      response-type: 'application/json'   # the only supported type
      response-format:
        is-vpn-field:
          # Nested fields are joined with '#': { "security": { "vpn": true } } -> security#vpn
          field-name: "security#vpn"
          field-type: 'BOOLEAN'            # BOOLEAN or STRING
          string-options:
            is-vpn-string: "yes"           # STRING only: this value (case-insensitive) means VPN
        vpn-provider-field:
          field-name: ""                   # optional, for example "security#operator"
        # Optional (0.5.1+): more facts shown in /cg explain and the dashboard and usable in rules.
        details:
          proxy: "security#proxy"
          tor: "security#tor"
          hosting: "security#hosting"
          asn: "network#asn"
          isp: "network#org"
          country: "location#country_code"
          risk: "risk"
      # Optional: locally counted limits, 0 = unlimited. Set them to your plan's limits.
      daily-budget: 0
      minute-budget: 0
```

How the answer is read:

- `is-vpn-field` is required. With `BOOLEAN` the field must be a JSON `true`/`false`. With `STRING` it is compared with `is-vpn-string`, ignoring case.
- A missing field or a field of the wrong type does not count as "no VPN". The lookup counts as failed, and your `failure-policy.vpn` decides what happens to that login. A missing optional `details` field simply stays unknown.
- `%IP%` also works in header values and field paths, for APIs that key their answer by address (for example `"%IP%#proxy"`).
- Custom providers vote like built-in ones and count towards `required-positive-flags`. From 0.6.0, the failover chain also uses them, with the section name as their ID in `provider.vpn-failover.order`.

The plugin checks the section when it loads the configuration. An invalid method, URL, header (it must contain `:`) or response type, or an empty `is-vpn-field`, is reported as a configuration error; on `/cg reload` the previous settings stay active. Check the provider's terms, limits and data protection information before sending player addresses to it.
