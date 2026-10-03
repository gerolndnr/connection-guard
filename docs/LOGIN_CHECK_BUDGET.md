# One budget for login checks

On Bukkit, BungeeCord and Velocity, `lookup.deadline-ms` is one per-login wait
budget. It starts at the guard decision capture, before permission resolution.
Permission loading, lookup admission, cache reads and VPN/Geo facts use its remaining
time. Waiting for LuckPerms cannot start another full lookup budget afterward.
Direct administrative/provider API queries retain their own bounded lookup deadline.

Each caller has a bounded session (`lookup.max-inflight`) and one completion.
Permission factories run on the existing bounded lookup workers, including a factory
that blocks before returning a future. Timely known exemptions avoid detector work.
An unresolved check ends as typed UNKNOWN/TIMEOUT, never a fabricated negative.
Failure policy and OBSERVE still determine admission. Shutdown or incompatible runtime
limits cancel the session; an expired permission grant cannot exempt that login.
`/cg stats` and `/cg doctor` include the number of active login checks.

Shared detector work is independent of a caller. Cancelling or expiring one login
cannot cancel another caller's same-IP lookup. A shorter deadline can preserve source
votes already available before its cutoff, with missing votes still UNKNOWN/TIMEOUT
and the configured quorum unchanged. Source vote, operator and arrival time are stored
as one record; a snapshot neither starts requests nor publishes or cancels shared work.
Late source answers may serve the still-live shared lookup/cache, but cannot create a
second platform decision, command or observer publication for the expired caller.

Every VPN/Geo query captures its runtime, limits and cache reference. A delayed cache
miss after its deadline cannot restart provider work on a replacement runtime after
reload, even when that runtime has a longer deadline. Provider health completion uses
the original runtime limits. This does not establish whole-policy versioned activation
or rollback; those remain a separate feature.

This is a check-wait budget, not a hard real-time JVM or third-party code sandbox.
Platform processing and scheduling add overhead. The guard cannot forcibly terminate
trusted addon code that ignores interruption. A permission provider's own internal
storage/transport also requires its native integration qualification.

## Qualification

Core regressions first reproduced a 200 ms configured budget lasting 381 ms when
permission and lookup waits were added. They now cover the shared budget, blocking
permission factory, late grant, independent same-IP callers, cancellation, timely
facts/exemptions, finite admission, partial quorum attribution and cutoff, native API
interface contract, and shutdown lock ordering. Two additional red-to-green cases
reproduced late VPN/Geo cache misses starting new provider calls after a 200-to-1000 ms
runtime replacement; both now perform no new call or cache write.

The [login-budget fixture](../ci/fixtures/login-budget/README.md) runs the actual guard
login event on named Velocity with the canonical LuckPerms API and an explicitly
synthetic authority. Its receipt pins the exact artifact and records measured cases.
Named Paper/Folia regression receipts alongside that source exercise the changed
listeners, actual offline backend logins, cache/UNKNOWN/OBSERVE, scheduler actions and
shutdown. They do not exercise native LuckPerms on those backends. Native LuckPerms,
Floodgate, authenticated forwarding and BungeeCord runtime proofs remain distinct.
