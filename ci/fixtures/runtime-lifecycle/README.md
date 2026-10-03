# Runtime lifecycle regression addon

Synthetic console-only source for Velocity 3.4.0 build 566 / Java 21. Never install
on a public server. The addon compiles against the combined plugin and actual proxy
as compile-only dependencies. Its JAR contains only fixture classes and its own
plugin descriptor; no second copy of Connection Guard.

Compile with `javac --release 17 -proc:none -cp connection-guard.jar:velocity.jar
-d classes RuntimeLifecycleFixture.java`, then package `classes/` plus
`velocity-plugin.json` at the JAR root. Use an isolated offline proxy bound to
127.0.0.1 with no player forwarding/backend, bStats disabled and 256 MiB maximum heap.
Select only provider ID `runtime-fixture`, with voting enabled. Disable built-in VPN
providers and Geo, exempt Geo for literal `127.0.0.1`, choose ENFORCE and VPN failure
CLOSED. Set lookup deadline 200 ms, HTTP timeout 100 ms, workers 1, queue capacity 2,
max-inflight 4, and circuit failure threshold 100 to isolate the lifecycle case.
Reload after the addon registers; registration alone is inactive.

A synthetic localhost login blocks the supplier but receives UNKNOWN/unavailable
at the deadline. `fixture-runtime status` reports the still blocked worker. Three
ordinary `cg reload` requests reject without changing active settings. Change workers
to 2 in the draft and repeat three times; it still rejects and reports exactly one
live lookup worker, configured workers 1 and one provider call. `fixture-runtime
release` settles the old supplier. Once status reports idle, deliberate reload accepts
workers 2. Clear localhost cache and a fresh login receives a fresh negative result
at the guard login phase. Stop the owned proxy.

[The receipt](velocity-2026-10-03.json) pins exact artifact, addon, fixture source and
proxy hashes. It covers the named offline login phase, without an authenticated
identity, a backend join or another platform runtime proof. LuckPerms pending-load
and timeout retirement behavior is separately covered in core contract tests.
