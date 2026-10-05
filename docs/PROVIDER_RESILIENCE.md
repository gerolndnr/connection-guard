# Keyless failover and offline protection (0.5.2 candidate)

This candidate is not a stable release. Hosting-only policy and the complete
`mc-antivpn-bench` acceptance matrix remain release gates.

## Lookup contract

New configuration files select `provider.vpn-strategy: FAILOVER` with
`required-positive-flags: 1`. ProxyCheck is first, IPQuery second, IP-API last.
Each source is tried sequentially. The first concrete POSITIVE or NEGATIVE
response stops the chain; providers are never polled for consensus in this mode.
UNKNOWN, exhausted quota, open circuits, 429, transport errors and malformed
responses advance to the next source, within the original whole-login deadline.
An unavailable source skipped locally receives no IP. A failed network attempt
can therefore disclose the same IP to a second or third provider. Set
`provider.max-external-attempts: 1` for at most one attempt per lookup; later
lookups can use a fallback once the failing source's circuit opens.

Existing configuration files are not rewritten. Missing strategy keeps their
legacy CONSENSUS behavior and selected providers. To enable the new chain, set:

```yaml
provider:
  vpn-strategy: FAILOVER
  max-external-attempts: 3
  vpn:
    proxycheck:
      enabled: true
      api-version: v3
      api-key: ''
    ipquery:
      enabled: true
    ip-api:
      enabled: true
```

Country checks remain separately configured. New files use geo `Disabled` and
an empty country blacklist: a VPN-only login does not send an additional geo
request. Enable a geo source deliberately when configuring country restrictions.
Existing geo selections are retained.

## Evidence and provider conditions

* **ProxyCheck:** [API](https://proxycheck.io/api/),
  [terms](https://proxycheck.io/terms/). 100 keyless IP queries/day;
  the operator's own free key raises that to 1,000. No key/account/IP rotation to
  circumvent limits. v2 `proxy=yes` with `vpn=1` is positive. v3 explicit VPN,
  Proxy or Tor, or concrete VPN operator evidence, is positive. Recognized
  `operator.services` VPN categories and a documented exact-name set (IVPN,
  Mullvad, NordVPN, Surfshark, Private Internet Access, ProtonVPN/Proton VPN,
  ExpressVPN, Windscribe) cover hosting exits with known VPN operators.
  Raw provider classification flags remain verbatim in source metadata.
  Hosting alone is reviewed and logged, not automatically denied.
* **IPQuery:** [API and terms/privacy statements](https://ipquery.io/).
  Keyless HTTPS; its official page permits commercial integration and states
  transient caching/abuse-prevention logging. Requires all `risk.is_vpn`,
  `is_proxy`, `is_tor` flags for a complete verdict. A missing flag is UNKNOWN.
  Hosting and risk score alone do not deny. This provider has no separately
  published DPA on the reviewed page; operators must assess their own data
  processing requirements. The fallback is not a claim of an executed AVV.
* **IP-API:** [terms](https://ip-api.com/docs/legal), last-resort keyless HTTP,
  45 requests/minute. Its free endpoint explicitly restricts use to
  non-commercial purposes and environments. Inclusion follows the project
  owner's explicit instruction; this document does not waive that restriction.
  Its broad `proxy` flag is positive evidence; it does not identify VPN, Proxy
  and Tor separately. Hosting alone does not deny.
* ipwho.is free has no security data; ipapi.is anonymous responses removed the
  detection flags on 1 September 2026. Neither is presented as keyless VPN detection.

IP literals are sent in compressed RFC 5952 form to ProxyCheck/IPQuery.
Equivalent compressed/expanded response keys match one canonical cache identity;
wrong or duplicate equivalent response keys are rejected.

## Offline Tor safety layer

The JAR includes an attributed Tor Project exit snapshot. A validated saved
snapshot takes precedence. Membership is checked locally before cache and APIs;
even a cached clean response cannot bypass it. A miss is not a negative verdict.
There is no login-time Tor request. Background refresh uses the official
[bulk list](https://check.torproject.org/torbulkexitlist), not player addresses,
normally every four hours, retrying failed downloads every five minutes.
Invalid/truncated downloads keep the last verified snapshot. Doctor reports age,
staleness and refresh failure. Retained stale membership still blocks: it cannot
cover newly introduced exits and may include formerly active exits. Staff can
use a scoped `/cg allow` exception. `CONNECTIONGUARD_TOR_REFRESH=false` disables
background downloads for controlled tests or fully offline deployments.

## Failure visibility and recovery

Console warnings name the provider and typed failure, without IPs, keys or URLs.
`/cg doctor` includes provider counters, local quota estimates and circuit state;
the existing Cloud `last_reason`/`paused` fields carry the same facts without new
personal data or new enum values. Local counters are not the provider account's
remaining balance. Transient circuits allow one half-open probe after at most
one second. Explicit HTTP 429 Retry-After is honored (up to 24 hours); older
in-flight successful answers cannot erase a rate-limit pause.

Redis startup does not depend on a successful network connection. A bounded
10,000-entry Memory cache is ready immediately; a dedicated daemon probes Redis
every two seconds and reconnects. No Redis credentials enter errors. Memory is
not persistent; local warm entries are retained through recovery. Outage-time
cache clears are reconciled before remote reads resume. SQLite remains supported.

## Modes and acceptance

Only new configuration files default to ENFORCE. Existing explicit OBSERVE or
ENFORCE values and the legacy missing-mode ENFORCE behavior remain unchanged.
A persisted notice is emitted once. VPN denial messages append a localized way
to request a staff `/cg allow` exception, including existing custom translations.

Release requires the owner to confirm hosting-only review and an extra immutable
candidate pin compared with 0.5.0 in `mc-antivpn-bench`. Detection must meet the
best competitor on the same measured cohorts, false positives must not increase,
burst concrete-check coverage must reach 95%, all four API faults must retain Tor
denial, cold p50 must remain approximately below 300 ms, warm approximately 4 ms,
and stampede/rejected-reload protection must pass. Core simulations and startup
smokes alone do not establish those comparative results.
