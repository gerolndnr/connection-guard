# Actual-JAR lookup profile qualification

`driver.py` compiles against the actual shaded archive and runs seven command,
cache, budget and HTTP-sharing cases on Java 8. It starts only an owned HTTP
endpoint on 127.0.0.1, sends documentation addresses and never contacts a provider.
The Build workflow retains the bound receipt. This is a contract test, not a
competitor accuracy/performance measurement or account-authentication test.

```sh
CONNECTIONGUARD_CLOUD=false CONNECTIONGUARD_INTEL_REFRESH=false CONNECTIONGUARD_TOR_REFRESH=false \
  python3 ci/fixtures/api-profiles/driver.py \
  --artifact build/libs/connection-guard-0.6.1-SNAPSHOT-all.jar \
  --java /absolute/path/to/jdk8/bin/java --work /absolute/path/to/new-private-directory
```

`native-driver.py` is a workspace-specific qualification driver using existing
owned runtime helpers and hash-pinned runtime files in the enclosing ownership
workspace. It is not a standalone public runtime installer. The versioned addon
sources/descriptors are in `../policy/`.

Named native targets on Java 21: Velocity 3.4.0-566, BungeeCord 26.1-2100,
Paper 1.21.11-132 and Folia 1.21.11-14. Only loopback/synthetic clients, private
test rules and three owned HTTP calls; no external provider API or telemetry.
Proxies use 256 MiB, backends 768 MiB; run sequentially and stop afterward.
Backend EULA acceptance must be consciously authorized beforehand.

Eight named cases:

1. Default CONFIGURED/0 keeps a real synthetic login denial and one HTTP call.
2. Profile previews preserve config bytes, mode and request count.
3. After the shared cap, 52 actual logins are UNKNOWN and counted with no new HTTP.
4. A fresh genuinely imported local list protects despite the empty cap/LOCAL_ONLY.
5. Local-only preserves a configured two-vote quorum and inconclusive results.
6. Explicitly raising the cap allows exactly one more call without resetting use.
7. Invalid profile reload leaves the previous valid runtime and cap in force.
8. Shutdown emits the actual partial-window unchecked warning with 52 budget cases.

The shutdown warning is **not** a measurement of five-minute scheduling. Existing
unit tests separately cover fixed reporting windows. Native results do not qualify
real accounts, every platform build, production latency or provider accuracy/FPR.
See [operator profile choices and privacy scope](../../../docs/LOOKUP_PROFILES.md).

Bound local receipts are in `receipts/`: 1,004 Redis-enabled regression tests,
three reproduced archives, seven actual Java 8 archive cases and eight native
cases on each named platform. Native receipts bind the production archive at
`830f984`; subsequent fixture retirement/documentation changes preserve its
`07bc227d80d7d63579905d7e58556e9e04eeb1ec39baf0cbbc8522c103ac6d56`
hash. The test retirement fix waits for physical executor termination before
reconfiguration; it does not weaken the product's runtime replacement guard.
The final PR CI independently re-runs its own archive cases and complete tests.
These receipts do not establish five-minute native scheduling, provider accuracy,
production performance or independent competitor acceptance.
