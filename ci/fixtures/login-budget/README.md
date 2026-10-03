# Synthetic permission and login-budget regression fixture

Test-only addon for Velocity 3.4.0 build 566 / Java 21. Never install on a public
server or alongside a real LuckPerms installation: it installs a scripted stand-in
through the canonical LuckPerms API 5.4 provider registration. It is not a native
LuckPerms plugin, identity authority or authentication test.

Compile `LoginBudgetFixture.java` with `javac --release 17 -proc:none`, using the
combined guard JAR, named Velocity JAR and official LuckPerms API 5.4 JAR as compile-only
dependencies. Package only the resulting fixture classes and `velocity-plugin.json`.
Do not bundle another Connection Guard or LuckPerms API copy. Put the canonical
LuckPerms API on the JVM parent classpath and run the proxy's manifest main class
`com.velocitypowered.proxy.Velocity`. Use an isolated offline proxy on 127.0.0.1,
no forwarding/backend, disabled bStats and 256 MiB heap. API SHA-256:
`086e3971ea63c0b5ad567881b2dfd955acbbffa24fe85ceac8abf54b114ce986`.

Disable built-in providers, select only voting `budget-fixture` and observer
`budget-observer`, disable Geo and exempt its localhost address. Use VPN permission
exemptions, empty VPN address exemptions, ENFORCE/CLOSED and the harmless console marker
command `fixture-budget marker`. Set deadline 250 ms, HTTP timeout 100 ms, workers 2,
queue 16, max-inflight 32 and circuit failure count 100. Registration alone is inactive;
reload after the addon has registered.

Console-only `fixture-budget set <permission-ms> <source-ms> <grant> <positive>`
controls delays, each bounded to 0–1000 ms. `fixture-budget status` reports user loads,
cleanups, source calls, observations, marker count and permission-factory thread.
Clear the localhost cache between cases. Synthetic Minecraft names must fit 16 chars.

1. With default untrusted offline identity and `500 20 true true`, a synthetic login
   is detected positive without permission loading or an exemption.
2. Deliberately select `identity.trust-forwarded-uuid: true` only in this isolated
   fixture to reach the existing declared-trust permission branch. This flag is not
   proof of authenticated forwarding. With `500 20 true true`, the login finishes
   UNKNOWN/unavailable near 250 ms. After the late grant, one loaded user is cleaned
   up, without a detector call, marker or second decision.
3. With `150 200 false true`, the permission and detector waits share 250 ms.
   The late positive produces no second decision/marker. One source call is recorded.
4. With `20 20 false true`, one timely positive yields exactly one configured marker.
5. With `20 20 true true`, a timely grant exempts the login without a detector call.
6. In OBSERVE, `150 200 false true` permits the unknown connection at the guard phase,
   without a denial or marker. Stop the owned proxy and verify clean shutdown.

[Velocity receipt](velocity-2026-10-03.json) pins the artifact, fixture, addon, API and
proxy hashes. Durations include guard platform processing, not complete backend login.
[Paper](paper-2026-10-03.json) and [Folia](folia-2026-10-03.json) receipts qualify the
same artifact's backend listener/scheduler regression cases with real offline clients;
those cases do not install this permission stand-in or native LuckPerms.
