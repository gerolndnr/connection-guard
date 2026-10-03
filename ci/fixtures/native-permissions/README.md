# Actual native permission contract on Velocity

These test-only addons use the real LuckPerms 5.5.85 and Floodgate 2.2.5 build 141
implementations on Velocity 3.4.0 build 566 / Java 21. No permission API stand-in is
installed. The provider's positive detection facts and player accounts are synthetic;
this is a permission/identity-boundary test, not a detection accuracy benchmark.

Use a private offline proxy on 127.0.0.1, no forwarding/backend, disabled metrics,
256 MiB heap, LuckPerms H2 and server `native-fixture`. Disable translation installation,
file watching and messaging. Disable Floodgate global/own linking and metrics. The
addons create only two synthetic offline users in the owned H2 test directory.
Compile with `javac --release 17 -proc:none` against the guard, proxy and actual native
plugin JARs as compile-only dependencies. Package only fixture classes and the selected
JSON descriptor as `velocity-plugin.json`; do not bundle guard/native APIs or add an
API to the JVM parent classpath. Native plugin classes and provider registration must
come from the actual installed plugins.

Select provider `native-perm-fixture` with voting enabled and observer
`native-perm-observer`, then reload after registration. Disable built-in VPN providers
and Geo, exempt Geo for localhost, empty VPN exemptions, enable VPN permission
exemptions, use ENFORCE/CLOSED and disable staff actions. Keep forwarding trust false
initially. Clear localhost cache between logins. All fixture commands are console-only.

`fixture-native prepare` loads/saves `CGNativeAllowed` and `CGNativeWrong`, granting
`connectionguard.exemption.vpn` in respectively `server=native-fixture` and
`server=different-fixture`. Only after both saves complete, helper queries must return
true/false using the real static server context. `fixture-native unloaded` verifies an
initially unloaded synthetic user receives no invented permission. `missing-id` returns
no grant for null UUID. `status` proves the guard sees the canonical LuckPerms API and
that Floodgate reports no membership/player record for an unregistered UUID.

A login as `CGNativeAllowed` with forwarding trust false must remain UNTRUSTED and be
flagged despite the real native permission. Deliberately selecting forwarding trust
in this isolated fixture reaches the declared-trust branch: the matching context
exempts that login; the wrong context remains flagged. This declaration is not verified
forwarding and cannot establish the identity authority for a temporary grant.

In the [native receipt](velocity-native-2026-10-03.json), the proxy is offline. The
[online proxy receipt](velocity-online-forced-offline-2026-10-03.json) instead enables
online mode globally while the test addon explicitly requests `forceOfflineMode()`
for the two exact synthetic names. A separate native event probe records
`proxyOnline=true playerOnline=false`. The guard still reports UNTRUSTED before the
explicit declaration. This exercises real per-connection status without creating an
authenticated session. It is a passing qualification, not a reproduced identity bug.
Velocity documents this override in its [official API](https://jd.papermc.io/velocity/3.4.0/com/velocitypowered/api/event/connection/PreLoginEvent.PreLoginComponentResult.html).

For [missing SDK](velocity-missing-sdk-2026-10-03.json), compile/package
`MissingPermissionFixture.java` with `velocity-missing-permission.json`, install no
LuckPerms/Floodgate/API JAR, and select the same test source/observer. Availability must
be false; a null UUID gives no grant. Even deliberately declared trust does not bypass
positive detection when LuckPerms is absent. Stop every owned proxy and verify shutdown.

Each receipt pins exact guard/addon/source/proxy/native JAR hashes. The Floodgate SHA
matches its official build metadata; LuckPerms metadata supplies a versioned HTTPS
artifact, whose observed hash is pinned here (no separate upstream signature/checksum
was verified). These named cases do not qualify authenticated Java/Bedrock sessions,
linked accounts, complete Geyser behavior, other platforms, native bans/challenges,
verified temporary grants or a complete version range.
