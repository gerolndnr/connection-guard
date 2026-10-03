# Platform task dispatch and metrics

The Bukkit adapter compiles to Java 8 against Spigot 1.8.8. On runtimes exposing
the current public Paper/Folia scheduler interfaces, it resolves global/entity
methods once and dispatches through those interfaces. Legacy Bukkit uses its main
thread scheduler. A partly available modern API or detected Folia without the
required interfaces fails initialization instead of using an unsafe fallback.

Console commands/replies and the online-player directory use the global scheduler.
Each player's address, permission check and reply/notification use that player's
entity scheduler. Directory targets support literal IPs and online name/UUID;
this Bukkit path does not resolve hostnames. If a target retires, its player work
is skipped and the requester can receive a target-unavailable reply. Retirement
does not send player work to a different region or the legacy scheduler.

Queued callbacks check plugin activation/closure before execution. Scheduler
failures produce a redacted warning limited to once per 30 seconds; no unsafe
fallback is attempted. Configuration/translation snapshots are published through
volatile references so async callbacks see completed reloads.

The prior bStats Bukkit 3.0.2 adapter supplied `BukkitScheduler.runTask` to its
collector. `PlatformMetrics` preserves the bStats configuration, aggregate fields
and service ID, and passes the same global dispatcher to the bundled, relocated
3.0.2 MetricsBase. Disable closes its timer before platform dispatch closes. The
MIT notice is retained. This avoids a separate Bukkit-adapter runtime download;
Velocity/Bungee adapters retain their own platform libraries.

All three `/cg info` adapters use an immutable display snapshot. Missing/stale
Geo fields and UNKNOWN VPN outcomes are displayed as `UNKNOWN`, with no invented
GeoResult or synthetic negative observation. This is a presentation view; it
does not change cached evidence or access rules.

## Runtime qualification

`ci/fixtures/platform-tasks` is an unshaded, synthetic addon compiled against the
combined plugin and the pinned Paper API. It checks global/entity ownership at
runtime, issues player admin commands, observes global flag actions, and asserts
that callbacks for a retired player are skipped. Its metrics probe invokes the
actual dispatcher and collector consumers supplied to pinned MetricsBase using
reflection; telemetry remains disabled and it never calls submission/network code.

Private loopback fixtures are started only after a conscious EULA decision, with
Java 21, at most 768 MiB, synthetic offline clients and sequential Paper/Folia
runs. Store the actual plugin/runtime/addon/source hashes and results. A result
qualifies the named versions and tested paths only. It does not prove authenticated
identity, legacy Spigot, other Minecraft versions or real bStats HTTP submission.

References:
- https://docs.papermc.io/paper/dev/folia-support/
- https://jd.papermc.io/folia/1.21.11/io/papermc/paper/threadedregions/scheduler/EntityScheduler.html
- https://jd.papermc.io/folia/1.21.11/io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.html
- https://repo.maven.apache.org/maven2/org/bstats/bstats-bukkit/3.0.2/bstats-bukkit-3.0.2-sources.jar

Completed on 3 October 2026: Paper **1.21.11 build 132** and Folia **1.21.11 build 14**
both pass against the same development JAR SHA-256
`fdf410ff74a151bcf6a32c2d2bbf0ddebe6c34224a106603a6d43e1395f0f19c`.
This is a development artifact, not the published 0.4.10 or 0.4.11 release.
The records are [Paper](../ci/fixtures/platform-tasks/paper-2026-10-03.json) and
[Folia](../ci/fixtures/platform-tasks/folia-2026-10-03.json), with
[reproduction instructions](../ci/fixtures/platform-tasks/README.md).

Both runs verify actual offline client joins, provider-positive login denial,
cache reuse without another HTTP request, OBSERVE admission without flag commands,
entity-context admin target access and async replies, global console actions,
staff notifications, skipped retired callbacks, literal IPv6 and consumer-failure
isolation, the real bStats dispatcher/collectors without submission, absent Geo
as UNKNOWN, and clean shutdown. Each run made two synthetic HTTP requests.
The existing `folia-supported` metadata is not evidence by itself.
