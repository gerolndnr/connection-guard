# Free anti-VPN software: understand provider quotas

> Development candidate: [provider resilience and migration](PROVIDER_RESILIENCE.md) describes 0.5.2-SNAPSHOT. Stable 0.5.0/0.5.1 setup instructions below retain their original defaults; new candidate installs use ENFORCE, sequential keyless failover and disabled geo lookups. Existing configuration files are preserved. Full comparative acceptance is pending.


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

VPN and geo lookups are separate. Multiple enabled VPN providers can each receive a request for an uncached IP. Expiration, restarts, failures and cache clearing affect volume. Repeated connections may use cached data.

Illustrative quota planning, **not a measured workload**: 100 uncached IPs with one VPN and one geo lookup can produce 200 requests. If both use the same provider account, they can draw from the same allowance. Check provider-specific counting rules.

SQLite is the simple initial cache. Shipped expirations are 1,440 minutes for VPN and 4,320 minutes for geo. Results can become stale after an IP changes use. Choose expiration for your policy; frequent clearing increases requests.

## What happens during an outage?

If the positive VPN vote threshold is not met, the connection proceeds, subject to geo rules and other plugins. A missing answer is not evidence that an IP is safe. Incomplete negative verdicts are not cached; a later connection can retry. An unavailable geo response provides no geo verdict. [Decision and caching table](PROVIDER_FAILURES.md).

If the configured cache cannot initialize, the plugin stops initialization. This does not stop the whole server or create a fail-closed connection policy. Check startup logs before relying on checks.

## Choose deliberately

Start with a provider whose terms permit your use and confirm its allowance. Add providers with a reason and enough quota, then select a meaningful vote threshold. Threshold changes are not guaranteed accuracy improvements.

Detection requests share IP addresses with selected providers. Consult their official privacy information when documenting your setup. Keep keys private. In 0.4.10, free IP-API uses HTTP; ProxyCheck and IPHub requests use HTTPS.
