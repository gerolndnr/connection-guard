# Synthetic decision-observer addon

Source-only, unshaded Velocity addon. **Never install on production.** It registers
one synthetic provider and one observer, initially inactive. Source observations,
country, ASN and risk are synthetic and are not an accuracy benchmark. No player
credentials, backend, provider account or API token is used.

Compile in a fresh directory against the locally verified plugin and proxy JARs:

```sh
javac --release 17 -proc:none -cp "$CG_JAR:$VELOCITY_JAR" -d classes DecisionObserverFixture.java
jar --create --file fixture.jar -C classes . -C . velocity-plugin.json
```

Use Java 21, a fresh isolated proxy bound to 127.0.0.1, offline mode, forwarding
disabled, telemetry disabled, at most 256 MiB and no backend. Install only the
combined plugin and this addon. Never expose this deliberately synthetic offline
fixture to other hosts. Select `decision-fixture` as its sole voting provider,
SQLite cache, geo disabled with loopback exempted, ENFORCE, VPN failure CLOSED.
Initially leave observers disabled. The scripted authentication-error sequence
uses a circuit failure threshold of 100 to separate those cases from the subsequent
observer queue test; it is not a test of the default provider circuit threshold.

All commands are console-only:

```text
fixture-observer status
fixture-observer provider positive|negative|unknown
fixture-observer observer normal|fail|block|release
fixture-observer close
fixture-observer replace
fixture-observer old-close
```

Select `observer-fixture` through `integrations.observers` and reload. Changing a
synthetic provider mode requires clearing the IP cache for a fresh lookup. Unknown
returns typed provider AUTHENTICATION. Callback failure emits the observation then
throws a synthetic exception, whose details must not reach guard logs. Blocking
uses a releasable latch; it must not block socket login decisions. Two callbacks
hold the workers; 80 sequential loopback clients fill 64 queue jobs and drop the
overflow. Disabling observers and reloading clears queued jobs. Release the two
callbacks before normal shutdown; the addon also releases them during shutdown.

Acceptance checks inactive registration; cached positive denial/source attribution;
negative allowance; positive OBSERVE allowance; UNKNOWN and source failure reason;
early literal-network deny with a rule trace and no provider call; close/replacement/
old-close activation rules; callback error isolation; actual queue bounds and login
independence; queued-job cancellation on disable; untrusted UUID withholding; shutdown.
ALLOW here describes the guard login phase; this proxy has no backend.

Completed on 3 October 2026 on Velocity 3.4.0 build 566 / Java 21, using development
JAR SHA-256 `f4eb59322fcbbcb2f63091906098e6e6553dd8b5ac0559769ae7937ea303b0ff`.
[Acceptance record](velocity-2026-10-03.json) includes runtime/source/addon hashes.
This is not the published 0.4.10 or 0.4.11 artifact.
This fixture does not prove authenticated identity or Bukkit/Bungee/Paper/Folia
observer behavior. The independently qualified platform scheduler fixture has its
own artifact hashes and scope.
