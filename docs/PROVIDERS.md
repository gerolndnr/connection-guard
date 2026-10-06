# Free anti-VPN software: understand provider quotas

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
| Custom provider | Configurable GET/POST API and response fields; see [Custom providers](#custom-providers) | Your provider's official documentation |

ProxyCheck currently advertises **1,000 daily queries** for its registered free plan. This is a query allowance, not a count of unique Minecraft players. Check your account's actual allowance.

The free IP-API endpoint allows **45 requests per minute per source IP**, uses HTTP and **does not allow commercial use**. Persistent overuse can trigger a temporary ban. Choose a suitable supported provider for your use; Connection Guard does not automatically purchase a plan.

## How requests accumulate

VPN and geo lookups are separate. Multiple enabled VPN providers can each receive a request for an uncached IP. Expiration, restarts, failures and cache clearing affect volume. Repeated connections may use cached data.

Illustrative quota planning, **not a measured workload**: 100 uncached IPs with one VPN and one geo lookup can produce 200 requests. If both use the same provider account, they can draw from the same allowance. Check provider-specific counting rules.

SQLite is the simple initial cache. Shipped expirations are 1,440 minutes for VPN and 4,320 minutes for geo. Results can become stale after an IP changes use. Choose expiration for your policy; frequent clearing increases requests.

## What happens during an outage?

If the positive VPN vote threshold is not met, the connection proceeds, subject to geo rules and other plugins. A missing answer is not evidence that an IP is safe. Incomplete negative verdicts are not cached; a later connection can retry. An unavailable geo response provides no geo verdict. [Decision and caching table](PROVIDER_FAILURES.md).

If the configured cache cannot initialize, the plugin stops initialization. This does not stop the whole server or create a fail-closed connection policy. Check startup logs before relying on checks.

## Choose deliberately

Start with a provider whose terms permit your use and confirm its allowance. Add providers with a reason and enough quota, then select a meaningful vote threshold. Threshold changes are not guaranteed accuracy improvements.

Detection requests share IP addresses with selected providers. Consult their official privacy information when documenting your setup. Keep keys private. In 0.4.10, free IP-API uses HTTP; ProxyCheck and IPHub requests use HTTPS.

## Custom providers

Any detection service with a JSON HTTP API can be added without code. Every section under `provider.vpn` whose name is not a built-in provider (`proxycheck`, `ip-api`, `iphub`, `vpnapi`, `ipqualityscore`, and from 0.5.2 `ipquery`) or `local` is read as a custom provider. The shipped file contains a disabled example called `custom`. Use one section per service, with any name you like.

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
- Custom providers vote like built-in ones and count towards `required-positive-flags`. From 0.5.2, the failover chain also uses them, with the section name as their ID in `provider.vpn-failover.order`.

The plugin checks the section when it loads the configuration. An invalid method, URL, header (it must contain `:`) or response type, or an empty `is-vpn-field`, is reported as a configuration error; on `/cg reload` the previous settings stay active. Check the provider's terms, limits and data protection information before sending player addresses to it.
