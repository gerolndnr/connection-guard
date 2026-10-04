# Native IPQualityScore — unreleased development

Available in the 0.5.0-SNAPSHOT development source; not part of stable 0.4.11.
This optional adapter uses an operator-owned IPQS key. It is disabled by default.
No Connection Guard account, shared key, paid plan or automatic upgrade is introduced.

## Configure deliberately

```yaml
operation:
  mode: OBSERVE
provider:
  vpn:
    ipqualityscore:
      enabled: true
      api-key: '' # Supply your own key locally; never share this configuration with it present.
      strictness: 0
      allow-public-access-points: true
      fast: true
      daily-budget: 30
      minute-budget: 5
```

An enabled provider with an empty/invalid key rejects the complete draft. Existing other
provider choices are retained; disable them explicitly if selecting IPQS alone. Review
`required-positive-flags` against the selected voting sources, then `/cg reload`,
`/cg doctor`, `/cg providers` and `/cg explain <IP>`. Begin with observation and inspect
actual cases before enabling denial. No default risk rule is added.

Strictness is 0..3; IPQS warns that levels 2+ can increase false positives. Allowing public
access points accommodates shared networks. `fast: true` omits some slower forensic checks.
The adapter supplies no invented mobile/browser context, player name, UUID, transaction or
fraud report. Options are explicit and changes derive a new detection cache namespace.
See the [official request options and header authentication](https://www.ipqualityscore.com/documentation/proxy-detection-api/advanced-options).

## Interpret actual source facts

`vpn`, `proxy` and `tor` are separate suspected classifications; their OR supplies the
ordinary provider vote. The documented proxy implication is checked. A high `fraud_score`
alone never creates a VPN vote. ASN, ISP and country are optional metadata; IPQS `N/A`
country/ISP and null ASN remain unknown. Organization is not labelled a VPN operator;
hosting, confidence and actual-active-VPN/Tor distinctions are not inferred.

The overall risk is source-reported 0..100 and is not a calibrated probability or proof
of abuse. Decimal risk is preserved in cache, policy, explain, provider API and decision
observers. Missing risk remains unknown; an explicit zero stays zero. Source-specific
integer thresholds compare the full decimal value: 79.999 is below 80. Determine the actual
source label using `/cg providers`; with IPQS first it is `IpQualityScoreVpnProvider#0`.
A selector `risk:IpQualityScoreVpnProvider#0:80` affects that source only; adding a DENY
rule is a separate conscious operator action. Validate your policy in observation first.
See the [official response definitions](https://www.ipqualityscore.com/documentation/proxy-detection-api/response-parameters).

## Quota, failures and privacy

Reviewed 4 October 2026: the [IPQS account page](https://www.ipqualityscore.com/create-account)
advertises 1,000 free credits **per month**. Check your actual account and current conditions;
this is not a daily allowance. The example local limits do not guarantee that monthly quota:
counts reset on restart, multiple processes and other account consumers can spend credits,
and unsuccessful calls may consume allowance. No account-balance query is made. Quota-aware
persistent/distributed hybrid budgeting remains separate unfinished work.

HTTP 429 and the documented HTTP-200 insufficient-credit error produce UNKNOWN/RATE_LIMIT
and a bounded pause, never a negative vote. Authentication, timeout, invalid responses and
other unsuccessful HTTP codes remain typed unknown failures. Source failures do not lower
the configured quorum. The configured OPEN/CLOSED/OBSERVE failure choice still applies.
Requests share normalized literal player IPs with IPQS via the fixed HTTPS endpoint; the
key is in `IPQS-KEY`, not in the URL. Redirects are disabled. Responses and exception messages
are not published. Cache stores validated required facts, not raw API responses.

Use your own account for your own server decisions; do not resell keys, publish provider
responses or operate a Connection Guard service that republishes IPQS data. Review
[IPQS terms](https://www.ipqualityscore.com/terms-of-service) and privacy/account requirements
for your deployment. Connection Guard neither signs up accounts nor changes account plans.
The [official error examples](https://www.ipqualityscore.com/documentation/proxy-detection-api/overview)
provide the credit-exhaustion contract used by this adapter.

## Qualification boundary

Fabricated loopback HTTP cases test field parsing, transport bounds, header privacy,
IPv6, precision, cache and policy/SPI flows. They do not establish an active IPQS account,
live upstream availability, real-world detection accuracy or a new platform version range.

A controlled [native Velocity fixture](../ci/fixtures/ipqualityscore/README.md) qualifies ten
real proxy cases, including source-specific decimal denial, positive/negative SQLite reuse,
observer metadata, reload budget retention and HTTP-200 credit pause. Its
[receipt](../ci/fixtures/ipqualityscore/runtime-receipt.json) identifies the exact tested JAR,
fixture sources and pinned runtime. This uses the package-private loopback test seam,
synthetic offline logins and an owned nonlistening backend target; it makes no backend-join,
independent account authentication or live-IPQS claim.
