# Synthetic provider API v1 addon

Source-only, console-controlled Velocity contract fixture. **Never install on production.**
The observations, country, ASN and risk are synthetic; they are not an accuracy benchmark.
No external data provider, backend server, player credentials or API tokens are required.
Connection Guard's ordinary `check` tests validate the core contract separately.

The successful run on 3 October 2026 used Java 21, Velocity **3.4.0 build 566**
(SHA-256 `fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e`),
and the development Connection Guard JAR SHA-256
`fa21cb6ac0432fa0827505d16870609ad6baeb79038fe015e3d42a7bc5401635`.
It is not a test of the published 0.4.10 artifact. Source/addon hashes and actual acceptance
results are recorded in [velocity-2026-10-03.json](velocity-2026-10-03.json).

To compile against those locally verified JARs, use a fresh directory:

```sh
javac --release 17 -proc:none -cp "$CG_JAR:$VELOCITY_JAR" -d classes ProviderContractFixture.java
jar --create --file fixture.jar -C classes . -C . velocity-plugin.json
```

Do not shade Connection Guard classes. The manifest declares the actual plugin dependency.
Start an isolated proxy bound to **127.0.0.1 only**, offline mode, no backend, telemetry disabled,
Connection Guard and this addon installed. This intentionally insecure synthetic login fixture
must not be exposed to other hosts. Select only the fixture provider, with explicit budgets,
geo disabled/bypassed and VPN failure policy CLOSED. All controls are console-only:

```text
fixture-api status
fixture-api mode positive|negative|unknown|hang|blocking|null|failure
fixture-api close
fixture-api register <new-id-or-same-id>
fixture-api old-close
fixture-api complete
```

Registration is initially installed but inactive. Reload after selecting each provider ID.
Close invalidates captured adapters; re-register alone does not activate a replacement.
Changing the synthetic response mode requires clearing cached facts if a fresh request is
intended. `hang` retains at most one synthetic pending future, cancelling its predecessor;
`blocking` sleeps for 1.5 seconds on the guard's bounded worker. Shutdown closes registration
and cancels the retained future.

Acceptance: default inactivity (zero callbacks); selected missing module UNKNOWN/NO_PROVIDER;
positive and negative SQLite reuse; close invalidates cached negative; replacing the same ID
requires reload and old-close cannot remove the new handle; voting mismatch and impossible
quorum preserve the active draft; two-query minute budget rejects the third without a callback;
32 real protocol-760 loopback login clients coalesce to one callback; 400 ms deadline bounds
pending/blocking logins; null and typed authentication failure remain UNKNOWN; clean shutdown.
Actual synthetic lookup time was 407 ms in both deadline cases; no production latency or
attack-capacity claim follows from this single run. Backend joins, authenticated UUIDs,
LuckPerms, Floodgate and Paper/Bungee/Folia are outside this fixture.
