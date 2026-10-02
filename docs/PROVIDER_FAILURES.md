# VPN provider failures and caching

`required-positive-flags` is the number of enabled providers that must return a positive VPN/proxy result. An unavailable response does not count as a positive vote. Providers returning no result, throwing a runtime exception or completing their future exceptionally cannot prevent healthy providers from contributing their votes.

| Provider results, with threshold 1 | VPN decision | Cache behavior |
| --- | --- | --- |
| All return a negative result | No VPN flag | Cache the complete negative verdict. |
| At least one returns a positive result | VPN flag | Cache the verdict, including when another provider is unavailable. |
| Negative results and at least one unavailable provider | No VPN flag for this attempt | Do not cache; query again on a subsequent connection. |
| All unavailable, or no providers enabled | No VPN flag for this attempt | Do not cache. |

For a higher threshold, the same rules apply: cache a positive verdict once the threshold is met; cache a negative verdict only when every enabled provider supplied a result. A missing response is not evidence that an IP is safe. The existing behavior allows a connection when the positive threshold is not met, subject to geo checks and other plugins. This change does not introduce a fail-closed mode, retry loop or new provider timeout.

The same core logic is used by Spigot, BungeeCord and Velocity. Existing cached results are reused without provider requests. Older entries do not record whether a provider was unavailable when they were created; they are not automatically invalidated by this change. After upgrading, if earlier provider failures may have produced stale entries, clear the affected IP with `/cg clear <IP>` or clear the entire cache with `/cg clear`. These commands require `connectionguard.command.clear` and clear both VPN and geo entries.

Core aggregation warnings identify the failed provider class without printing its exception message or stack trace, which may contain a request URL, IP or API key. This does not change logging inside individual providers.

Regression tests use local provider doubles and an in-memory cache to cover recovery for the same IP, partial results, vote thresholds, cache hits, synchronous/asynchronous/cancelled failures, delayed responses and warning privacy. They do not establish detection accuracy or server compatibility.
