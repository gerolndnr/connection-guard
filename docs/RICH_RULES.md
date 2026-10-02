# Source evidence and metadata rules

Development features for the next release. The published 0.4.10 JAR does not contain them.

Each source retains its generic verdict and its actually reported VPN, PROXY, TOR, RELAY and HOSTING observations. Missing fields remain unknown. Hosting, ASN or country alone is not evidence of abuse. The generic positive-vote threshold stays in force; additional metadata rules are explicit operator choices. No hosting, ASN or risk ban is enabled by default.

## Source capabilities

| Source | Separate observations | Other fields when present |
| --- | --- | --- |
| VPNAPI | VPN, proxy, Tor, relay | ASN, organization, country |
| IP-API | Hosting; the generic proxy flag includes VPN/Tor and is not relabelled as a separate proxy classification | ASN, ISP, country |
| IPHub with `Accept-Version: 2.2` | Proxy, Tor, hosting, relay | ASN, ISP, country |
| ProxyCheck v2 | One reported type, without inventing false values for other types | ASN, ISP, country, risk |
| ProxyCheck v3 | VPN, proxy, Tor, hosting | ASN, ISP, operator, country, risk, detection confidence |

Adapters follow the [VPNAPI](https://vpnapi.io/api-documentation), [IP-API](https://ip-api.com/docs/api:json), [IPHub](https://iphub.info/api) and [ProxyCheck](https://proxycheck.io/api/) contracts. The [ProxyCheck OpenAPI spec](https://proxycheck.io/resources/proxycheck-openapi.yaml) puts v3 risk at the address level, country/city under `location.isocode`/`location.city` and confidence under detections. v3 is pinned to `24-June-2026`.

The new template sets `provider.vpn.proxycheck.api-version: v3`. An existing config without this setting keeps v2. Either explicit version is accepted; invalid versions reject the draft before activation. Absent IPHub 2.2 metadata preserves a generic verdict without fabricated classifications. Neither averaging risk scores nor interpreting a score of 80 as an 80% chance of abuse is supported. Provider votes can be correlated.

## Commands

```text
/cg deny add type:TOR vpn 2h Investigated Tor policy
/cg deny add asn:AS15169 vpn 15m Synthetic example only
/cg deny add operator:Fixture VPN Company vpn 1h Synthetic example only
/cg deny add risk:ProxyCheckVpnProvider#0:80 vpn 1h Synthetic example only
/cg deny add confidence:ProxyCheckVpnProvider#0:90 vpn 1h Synthetic example only
/cg allow add isp:Fixture ISP vpn 15m Synthetic example only
/cg deny add country:DE geo 2h Synthetic example only
/cg deny list
/cg deny remove <rule-id>
```

Examples are not recommended bans of real networks/countries. The existing allow/deny/exempt permissions, expiry, reasons, private atomic store and scopes apply. ASN accepts decimal with optional `AS` prefix, up to AS4294967295. ISP/operator names match exactly, ignoring case, without substring matching. Country uses uppercase two-letter codes. Type accepts VPN/PROXY/TOR/RELAY/HOSTING. Score rules use `>=` against **one exact source ID** from `/cg explain`; other sources' scores cannot satisfy it. IDs contain the enabled-provider index: recheck selectors after reordering providers. Multiword ISP/operator names are accepted before scope/duration, optionally surrounded by double quotes.

## Precedence

1. Address/authenticated identity rules: DENY > ALLOW > EXEMPT. An explicit address/trusted-UUID grant precedes metadata and skips that scope's lookup. Configured and permission exemptions also bypass that scope; a claimed name never authenticates identity.
2. Metadata rules: DENY > ALLOW > EXEMPT. Positive evidence can deny despite another missing/conflicting source. A metadata grant requires available, agreeing evidence and cannot override an unresolved metadata deny. Geo sources do not vote on type/operator fields they cannot provide.
3. Existing generic VPN threshold and country policy, subject to scoped grants.

Unknown scores stay unknown. Non-score source disagreements are CONFLICT; incomplete evidence is UNKNOWN. Unresolved rules follow the scope's failure policy: OPEN/OBSERVE permits, CLOSED temporarily refuses verification. Global OBSERVE suppresses all access denials. Metadata denials use a generic rejection without the private reason and do not automatically execute punishment commands or webhooks.

`/cg explain <literal IP>` displays individual fields/status/failure/duration, cache age, matched rule and up to 20 metadata evaluations per scope. This explicit lookup may spend quota. It does not authenticate a player's UUID, apply their permission context or yet replay the entire country/punishment policy. Full simulation and policy activation remain part of the subsequent differentiation package.

## General REST metadata

Optional mappings use the existing `#` path separator. Missing/null fields remain unknown; wrongly typed values invalidate that source. Omit unsupported mappings:

```yaml
response-format:
  details:
    vpn: data#security#vpn
    proxy: data#security#proxy
    tor: data#security#tor
    relay: data#security#relay
    hosting: data#security#hosting
    asn: data#network#asn
    isp: data#network#isp
    operator: data#operator#name
    country: data#location#isocode
    risk: data#risk
    confidence: data#confidence
```

Classifications require JSON booleans, scores integer JSON numbers 0–100, ASN integer/decimal string in 1–4294967295. Text is bounded to 200 characters without controls or formatting injection. Native echoed addresses must match the request when present. The rich schema changes the hashed cache namespace, preventing reuse of earlier Boolean-only entries. Rich evidence and its original cache time survive SQLite/Redis serialization. UNKNOWN is never cached as a clean generic verdict. Third-party provider invocation runs in the bounded worker pool; a blocking extension cannot hold the initiating event thread beyond its lookup deadline.
