# Connection Guard 0.5.1 qualification

Maintenance release based on the complete 0.5.0 source history. The functional change initializes Velocity bStats with the verified project ID 22913, supplies its injected factory from the combined JAR and shuts down its Metrics instance. bStats failures do not interrupt connection checks. Global bStats opt-out remains supported.

The release also updates website descriptors and documentation/branding, and includes reproducible developer benchmarks in the repository. Benchmark policy, failure and cache-recovery fixtures use controlled facts; they do not establish provider detection accuracy, production capacity or a measured latency improvement. The preregistered latency study has not yet produced a comparative latency dataset.

Publication requires successful exact-source CI, all 431 plugin regression tests (including real loopback Redis), artifact-bound benchmark gates, repeatable main/addon archives and clean hash-bound startup/help/reload/shutdown checks with the final JAR. The selected runtime checks are Paper 1.21.11 build 132, Velocity 3.4.0 build 566 (Java 21), Paper 26.3 build 151, Folia 26.2 build 7, Velocity 4.2.0 build 30 and Velocity 4.2.1-SNAPSHOT build 36 (Java 25). Current Paper/Folia builds are upstream beta builds and Velocity 4.2.1 is a development snapshot.

A separate actual Velocity factory/collector fixture verifies project ID 22913, opt-out, reload and clean shutdown while telemetry is disabled. This is not proof of live bStats ingestion. Startup checks do not qualify every intermediate Minecraft version or repeat the earlier identity/login/Cloud fixtures; those retain their original artifact hashes and limits.

No configuration migration or Cloud protocol change is introduced. Cloud remains on by default and optional: `cloud.enabled: false`, `/cg cloud disable` or `CONNECTIONGUARD_CLOUD=false`. [Plugin privacy disclosure](https://connectionguard.net/privacy#plugin). Stop the server/proxy and keep only one main JAR when upgrading. A stopped-server rollback to the backed-up 0.5.0 JAR and directory remains available.

Download and docs: https://connectionguard.net/download
