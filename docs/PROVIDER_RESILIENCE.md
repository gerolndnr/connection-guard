# General provider failover and offline protection (0.5.2 candidate)

This candidate is not a stable release. Hosting-only policy and the complete
`mc-antivpn-bench` acceptance matrix remain release gates.

## Lookup contract

`provider.vpn-failover.enabled: true` enables sequential failover for **all selected
VPN sources**: anonymous or keyed ProxyCheck, IPHub, VPNAPI, IPQualityScore,
IPQuery, IP-API, custom HTTP providers and explicitly selected API extensions.
It is enabled by default even in existing files without a strategy selection.
It does not enable extra sources, replace keys, change an existing ENFORCE/OBSERVE
mode, or rewrite the file.

The first concrete voting POSITIVE or NEGATIVE answer stops the chain. UNKNOWN,
quota exhaustion, open circuits, 429, transport errors and invalid/incomplete
answers advance to the next source within the same original whole-login deadline.
Non-voting enrichment cannot supply a threshold vote or terminate the chain.
A locally skipped source receives no IP. A failed network attempt can disclose the
IP to another source; this is one request at a time, not guaranteed one recipient
in the presence of failures. No consensus is requested while the chain is active.

```yaml
provider:
  vpn-failover:
    enabled: true
    order: []
  max-external-attempts: 16
```

An empty order puts local observations first, then ProxyCheck, IPQuery, other
selected sources in their declared order, and IP-API last. Set `order` to provider
section IDs such as `[ipqualityscore, iphub, corporate, proxycheck]`, or selected
extension IDs such as `extension.owned`. Unlisted enabled sources remain fallbacks;
disabled sources are never activated by this list. Local observations still precede
APIs and IP-API remains last. Duplicate, unknown or invalid IDs reject the complete
reload draft. Each source retains its own configured budgets when reordered.
Changing the order or mode changes the fact-cache namespace.

To restore the previous **parallel voting** behavior, change just:

```yaml
provider:
  vpn-failover:
    enabled: false
required-positive-flags: 2 # use your previous value, normally 1
```

The existing provider selections and configured quorum remain stored. In the chain,
the effective quorum is 1; a stored higher quorum produces a diagnostic notice and
applies again when the chain is disabled. `/cg doctor` reports the active mode,
effective threshold, order and maximum attempts. The dashboard quorum alone cannot
switch off sequential failover; change this local option before using a multi-provider
quorum. No new dashboard control is advertised by this plugin-only change.

The earlier candidate's explicit `provider.vpn-strategy: CONSENSUS` or `FAILOVER`
remains accepted if the boolean option is absent. The new boolean takes precedence.
Malformed legacy values are rejected. The default attempt limit covers up to all
16 allowed sources; an explicitly stored `max-external-attempts: 3` stays 3.
Set it to 1 to limit a lookup to one network attempt; locally skipped circuits or
quotas can still lead to a different usable source. Restarting a process resets
local usage counters; changing the order or switch does not reset retained counters.

New files enable anonymous ProxyCheck v2 (`vpn=1`), IPQuery and IP-API as the initial free
selection. Other keyed and custom sources remain explicitly selected by operators.

Country checks remain separately configured. New files use geo `Disabled` and
an empty country blacklist: a VPN-only login does not send an additional geo
request. Enable a geo source deliberately when configuring country restrictions.
Existing geo selections are retained.

## Evidence and provider conditions

* **ProxyCheck:** [API](https://proxycheck.io/api/),
  [terms](https://proxycheck.io/terms/). 100 keyless IP queries/day;
  the operator's own free key raises that to 1,000. No key/account/IP rotation to
  circumvent limits. VPN checks use the official v2 endpoint with `vpn=1`,
  `asn=1` and `risk=1`; [ProxyCheck documents v2 support until 2035](https://proxycheck.io/api/?db=1).
  This also applies to retained `api-version: v3` configurations: v3 hosting-only
  replies missed explicit v2 VPN signals in the recorded benchmark sample.
  `proxy=yes` is positive unless the only reported type is Hosting.
  An operator name alone is never detection evidence; there is no brand-name list.
  Hosting alone is reviewed and logged, not automatically denied.
  When VPN and geo both select ProxyCheck, they share this one v2 response and
  one local quota reservation, even when a retained setting requests v3.
  The other scope can consume a completed response within the login deadline;
  repeated VPN-only lookups still obey the configured persistent cache policy.
  This bounded in-memory handoff holds at most `lookup.max-inflight` entries,
  expires eligibility after `lookup.deadline-ms`, and discards completed old
  entries on subsequent lookups/source replacement. No second API is queried
  for missing geo fields. Standalone ProxyCheck geo retains the selected API version.
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
remaining balance. Circuits honor `lookup.circuit.pause-ms` (the unchanged
30-second shipped default), then allow only one half-open probe. Explicit HTTP 429 Retry-After is honored (up to 24 hours); older
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

## Unchecked admissions and comparison boundaries

Every actual CG-allowed login with an UNKNOWN VPN result increments a bounded,
anonymous coverage counter, even with Cloud and external observers disabled.
A successful fallback (negative or positive), an intentional VPN exception, a
CG-denied login, and `/cg explain` do not increment it. An incomplete consensus
without a conclusive result is counted. One login contributes once, with the
first failed voting source's reason; coalescing does not collapse login counts.

`/cg doctor` and `/cg cloud status` show `uncheckedAllowed`, the current summary
window and cumulative reasons. Every five minutes the console warns with the
window's count and reasons; zero windows stay quiet. Shutdown reports a final
partial window. Reload retains the counters. No IP, UUID, name or key is kept.
Cloud status includes optional `vpn_unchecked_allowed` anonymous totals, with
same-batch/sequence fallback for APIs that have not deployed this field yet.
Local counts remain exact if an older API rejects the extension; doctor/cloud
status explicitly report that compatibility limit.

HTTP timeout 2,500 ms, 8 workers, queue 64, max-inflight 128 and circuit pause
30,000 ms retain 0.5.1 defaults. There is no hidden one-second circuit override.
Geo is Disabled for new installs. Controlled test limits are explicit fixture
settings, not evidence that shipped defaults satisfy the burst/recovery gates.
This changed candidate requires a new exact-JAR comparison: detection/FPR,
all four API faults with local Tor, recovery, cold latency, ≥95% concrete burst
checks including failover, stampede, bad reload, and periodic unchecked warnings.
Historical measurements of the earlier operator-name candidate cannot qualify
this one. The owner's temporary warm-latency waiver remains separate.
