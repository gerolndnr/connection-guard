# Bounded lookups and diagnosis

Optional login workload limits: [lookup admission and cooldowns](OVERLOAD.md).
Shared raw facts and per-server rules: [Redis network cache](NETWORK_CACHE.md).
Explicit addon selection: [versioned provider contract](PROVIDER_API_V1.md).

Development documentation for the next release. Do not infer these features from the unchanged 0.4.10 tag.

New installations use `operation.mode: OBSERVE`: classification and staff notices remain available; kicks, console commands and webhooks are suppressed. Country blacklist starts empty. Existing files without this setting retain `ENFORCE`; no upgrade silently rewrites operator choices. To activate blocking, deliberately set `ENFORCE` and use `/cg reload` after reviewing `/cg doctor`.

`failure-policy.vpn` and `.geo` accept `OPEN`, `OBSERVE`, `CLOSED`. Missing or incomplete answers are **UNKNOWN**, not negative. `OPEN`/`OBSERVE` allow them; `CLOSED` temporarily denies a login with a verification-unavailable message, without executing bans, normal positive-result commands or webhooks. Global OBSERVE overrides denials. A healthy vote does not lower `required-positive-flags` after an outage. Positive threshold decisions remain cacheable; incomplete negative answers do not.

| Setting | Default | Meaning |
| --- | --- | --- |
| `lookup.deadline-ms` | 5000 | Total lifetime including cache access and provider queue |
| `lookup.http-timeout-ms` | 2500 | HTTP call deadline; cannot exceed total lifetime |
| `lookup.workers` | 8 | Maximum transport workers |
| `lookup.queue-capacity` | 64 | Maximum queued transport jobs |
| `lookup.max-inflight` | 128 | Maximum distinct active VPN/geo lookup keys |
| `lookup.circuit.failures` | 3 | Failures before temporarily pausing a source |
| `lookup.circuit.pause-ms` | 30000 | Circuit pause; 429 honors a longer numeric Retry-After/X-Ttl |

The same canonical IP shares each ongoing VPN lookup and each ongoing geo lookup; cancelling one caller does not cancel everyone else. IPv4-mapped IPv6 addresses map to IPv4. No hostname resolution is offered in the new operations commands. Limits bound this plugin's work; they are not network-level DDoS protection.

Each enabled `provider.vpn.<name>` can set `daily-budget` and `minute-budget`; `provider.geo` supports them too. Zero means no locally imposed limit. Default ProxyCheck limit is 100/day without a key, 1000/day with one; free IP-API is capped at 45/minute. VPN and geo calls to the same native service share one quota counter and the stricter nonzero configured limit. Daily counters reset at UTC midnight and when the plugin process restarts, so they are local estimates; other servers and tools also consume account quotas. Account-wide remaining quota is **not** reported. Quota exhaustion yields UNKNOWN. Numeric provider 429 backoff is bounded to one hour. Errors, queues and missing fields retain a reason code; 30-second log cooldowns avoid a repeated provider warning for each player.

Commands and permissions:

- `/cg doctor` — configuration/mode/limits/cache selection/forwarding warnings, no external request; `connectionguard.command.doctor`.
- `/cg providers` — sanitized health, attempts and local budgets; `connectionguard.command.providers`.
- `/cg local status|prepare|reload|import|update` — optional attributed local data, freshness and atomic activation;
  `connectionguard.command.local`. See [local data setup and limits](LOCAL_DATA.md).
- `/cg stats` — current workers/queue/inflight/shared/rejected counters; `connectionguard.command.stats`.
- `/cg explain <IPv4/IPv6>` — active VPN votes/status/cache age and geo status, may consume API quota; `connectionguard.command.explain`.

`doctor` does not prove network access without a real lookup. Check the observed client IP against a controlled test client; an apparently public IP alone cannot prove forwarding configuration.

## Identity and permission exemptions

Exemption lists accept literal addresses and UUIDs. Claimed pre-authentication player names never bypass checks. UUIDs are honored only in an authenticated online-mode phase, or with the operator's explicit `identity.trust-forwarded-uuid: true`. Enable that only after locking down backend access and verifying trusted forwarding. Unprotected offline servers cannot authenticate a UUID from a name. Missing UUID and missing/failing LuckPerms never grant an exemption or throw an exception.

Velocity performs provider/identity decisions once in LoginEvent, so pre-login client-supplied identity cannot grant a bypass and expiring rules or a reload between phases cannot skip checks. Literal network denials also reject early in PreLoginEvent. Bungee uses LoginEvent; Spigot uses AsyncPlayerPreLoginEvent. LuckPerms checks use existing users, loading only when necessary; a 750 ms deadline bounds loading. The subject context is used where available, otherwise the user's/static query options; a world context unavailable before backend join is not invented. Effective exemptions are resolved **before** provider requests. New forwarded-identity/Geyser/Floodgate compatibility must be tested for the actual installation; no blanket guarantee is made.

## Configuration and cache upgrades

Reload builds and validates a complete provider/settings draft before publishing it. Invalid limits, threshold, enabled key-requiring providers, custom URLs/fields, webhook settings and cache selection reject the draft. Cache connection changes require a restart. Changing runtime limits requires no active lookup at the moment of activation; if busy, retry after traffic subsides. Provider budgets do not reset on a normal reload.

SQLite and Redis use the v2 storage layout with an isolated SHA-256 configuration namespace including provider settings, vote threshold and rich-evidence schema version. Different configurations cannot read or clear one another's entries. Old tables/hashes stay untouched and are not imported because they lack decision provenance. The rich schema changes its namespace, preventing reuse of Boolean-only observations. SQLite serializes access to its connection, closes statements/result sets, replaces one row per key and cleans expired entries. Redis honors its ACL username, uses a serial connection with connect/read deadlines and per-key server TTL. Clear operations target the current namespace, never `FLUSHDB`/`FLUSHALL`. Rich traces and original cache time survive round trips without Java Optional reflection. Cache I/O has its own bounded queue; a cache outage cannot turn a positive verdict negative or exceed the total lookup deadline.

Use TLS for remote Redis. The four-argument legacy constructor retains its prior non-TLS behavior; the five-argument API constructor allows TLS. Set `provider.cache.redis.tls: true` for TLS using the JVM trust store; changing the connection requires a restart. Network integration evidence is recorded separately. Keep cache data and credentials private; TTLs are a retention limit, not permanent player histories.

Webhook transport has a separate finite queue, 2.5-second call deadline, no redirects, closed responses and sanitized failures. API JSON bodies are bounded to 256 KiB. Diagnostics never include request URLs, API keys, cache connection errors or webhook tokens. An explicitly requested explain command is access-controlled.

Primary API contracts used: [Velocity events](https://docs.papermc.io/velocity/dev/event-api/), [PreLoginEvent](https://jd.papermc.io/velocity/3.4.0/com/velocitypowered/api/event/connection/PreLoginEvent.html), [LoginEvent](https://jd.papermc.io/velocity/3.4.0/com/velocitypowered/api/event/connection/LoginEvent.html), [LuckPerms API](https://luckperms.net/wiki/Developer-API), [Jedis](https://github.com/redis/jedis). Tests pin the repository's LuckPerms 5.4 and Jedis 5.0 APIs rather than assuming newer client documentation is source-compatible.
