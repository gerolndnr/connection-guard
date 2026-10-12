# Explicit local observation capture qualification

The public offline driver compiles the fixture against the **actual shaded JAR**
and exercises capture permissions, redaction, explicit identity mode, literal-rule
replay, restart and shutdown. It uses synthetic inputs and no network/provider
request. A supplied trusted UUID is an input assumption, not account authentication.
The Build workflow runs these eleven cases on Java 8 and retains the receipt.

```sh
CONNECTIONGUARD_CLOUD=false CONNECTIONGUARD_INTEL_REFRESH=false CONNECTIONGUARD_TOR_REFRESH=false \
  python3 ci/fixtures/policy-observations/driver.py \
  --artifact build/libs/connection-guard-0.6.1-SNAPSHOT-all.jar \
  --java /absolute/path/to/jdk8/bin/java --work /absolute/path/to/new-private-directory
```

The native driver is a **workspace-specific** qualification tool, using the
existing local runtime helpers and hash-pinned runtime files in the enclosing
ownership workspace. These helpers are not a standalone public runtime installer.
The addon sources and descriptors are versioned in `../policy/`. The retained
native receipts name the exact artifact, driver, addon and runtime hashes; they
do not imply every platform version, real account or competitor was tested.

Named local stacks on Java 21: Velocity 3.4.0 build 566; BungeeCord 26.1 build
2100; Paper 1.21.11 build 132; Folia 1.21.11 build 14. Servers bind only to
127.0.0.1, built-in providers/Cloud/Intel/Tor refresh and telemetry are disabled,
and only the owned synthetic `policy-fixture` source is selected. Proxies use
256 MiB, backends 768 MiB, sequentially and with subsequent shutdown. Minecraft
EULA acceptance must be consciously authorized before using a backend.

Eleven cases per native stack:

1. Default off keeps an actual synthetic login denial and creates no archive.
2. Active capture adds no provider requests, actions or observer event.
3. Stop saves redacted inputs from the final evaluation.
4. Historical replay compares policies without new lookups/actions.
5. Restart preserves the archive without resuming capture.
6. An existing archive cannot be overwritten by another capture.
7. Explicit identity mode retains the owned loopback address.
8. A candidate literal DENY cannot activate or execute actions through replay.
9. A corrupt optional observation file leaves live admission functioning.
10. Rejected invalid configuration preserves capture and the active live policy.
11. Shutdown discards unsaved observations.

The initial native run exposed a real capture-schema rejection for valid empty
Geo inputs from the disabled provider. The regression test and corrected native
receipts cover this boundary; it does not change live detection behavior.

These cases qualify the observation workflow. They do not measure live provider
accuracy/FPR, benchmark superiority, every account/forwarding context or capture
performance at production traffic. Data-handling and replay limits are in
[POLICY_REPLAY.md](../../../docs/POLICY_REPLAY.md).
