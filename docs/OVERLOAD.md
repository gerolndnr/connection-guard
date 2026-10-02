# Optional lookup admission protection

Development feature for the next release. This protects Connection Guard's detection workload;
it is not packet filtering or volumetric DDoS protection.

`overload.enabled` defaults to `false`; each attempt limit defaults to `0` (disabled).
Choose limits from your own legitimate traffic, including households, mobile networks,
CGNAT and planned events. There is no automatic three-player/IP rule. A limit counts
connection attempts needing at least one detection scope, not online players.

Admission runs once per real login, after literal/trusted-identity and permission exemptions,
before either VPN or Geo lookup. Existing manual DENY remains earlier and independent.
If both scopes are exempt, no admission counter is charged. Administrative `/cg explain`
does not charge login counters. Native queue, deadline and provider budgets remain bounded
independently; cache hits also pass the admission gate.

When a limit refuses admission, the plugin skips both detection scopes. No VPN/Geo finding,
flag notification, punishment command, ban or detection webhook is generated. With
`deny-connections: false`, the connection passes this gate without a classification.
With `deny-connections: true` and operation mode ENFORCE, the connection is temporarily
refused with a retry message. OBSERVE suppresses this refusal while still protecting lookup
work. Provider `failure-policy` does not override this separate, explicit admission choice.
Skipping detection trades verification for bounded workload; choose denial deliberately
if that tradeoff is unsuitable for your server.

Example for controlled testing only, **not a recommended production rate**:

```yaml
overload:
  enabled: true
  deny-connections: true
  window-ms: 10000
  cooldown-ms: 10000
  alarm-cooldown-ms: 30000
  global-attempts: 100
  per-ip-attempts: 20
  per-subnet-attempts: 60
  ipv4-prefix: 24
  ipv6-prefix: 64
  tracking-capacity: 4096
```

Counters use monotonic time and fixed windows started on first use. Fixed-window boundaries
can admit a burst spanning two windows; this is not a sliding-window rate guarantee.
An IP/subnet reaching its limit receives its own cooldown. Further refusals do not extend
that cooldown. On expiry that counter starts a fresh window. IPv4-mapped IPv6 shares the
IPv4 counter and subnet. Native IPv6 remains separate, using the selected IPv6 prefix.

Only attempts that pass IP/subnet/capacity checks charge the global counter. Repeated attempts
from one already-limited IP cannot push unrelated connections into a global pause. Global
pressure above the explicit global limit starts a bounded GLOBAL_PAUSE; attempts during it
make no provider call and do not prolong it. At expiry the global counter starts a fresh
window and returns to NORMAL. Local counters retain their independent windows/cooldowns.

Tracking is bounded to `tracking-capacity` records **combined across IP and subnet counters**
(16..16384). It does not evict a live counter to accommodate a new address: new untrackable
admissions receive TRACKING_CAPACITY, preventing address churn from defeating existing limits.
Expired records are reclaimed on new admission, periodically by window or when capacity is
needed. Reclamation inspects at most the bounded capacity. No background worker or IP history
file is created. Records live only in plugin memory.

`/cg stats` and `/cg doctor` expose aggregate, address-free state, tracking usage, admissions,
skipped attempts and global retry time. A single shared alarm cooldown bounds warning logs;
warnings contain a reason and aggregate counts, never a player name or address. They do not
send messages to external services. `alarm-cooldown-ms` must be 1000..3600000; window and
cooldown are 100..600000 ms. Each configured attempt limit is 0..1000000. IPv4/IPv6 prefixes
are 0..32 and 0..128 respectively. An enabled configuration requires a nonzero limit.

All settings are validated in the complete reload draft. Invalid settings preserve active
configuration. A successful quiescent reload deliberately starts fresh admission counters;
limits are not persisted across reload/restart. Changing limits while lookups are active is
rejected by the existing quiescent configuration contract. A login already admitted remains
subject to ordinary detection and policy; it is not retroactively refused by a later pause.

Controlled-clock tests cover independent local/global limits, bounded churn, normalization,
cooldowns, concurrent callers, observation and alarm recovery. Runtime evidence uses a real
Velocity process and synthetic loopback detection. That does not establish attack-scale
capacity, production detection accuracy or untested platform compatibility.
