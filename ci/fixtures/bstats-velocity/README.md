# Velocity bStats qualification

`VelocityMetricsFixture.java` is a compile-only addon for an owned Velocity 3.4.0 build 566 / Java 21 proxy. It contains no copy of Connection Guard or bStats. Never install it on a public proxy.

The real plugin is injected by Velocity, including the relocated official bStats 3.0.2 factory. The fixture reads the created SDK instance and its real MetricsBase, checks `serviceId=22913` and `enabled=false`, then calls its actual platform/service data collectors. It verifies aggregate player/backend count and plugin-version fields without calling `submitData` or `sendData`. Run the command again after reload; stop the owned proxy.

Prepare a **complete** fixture `plugins/bStats/config.txt`, including `enabled=false` and a synthetic `server-uuid`. bStats MetricsConfig 3.0.2 regenerates defaults, including `enabled=true`, if the UUID is absent. A bare `enabled=false` file is insufficient evidence that its SDK is disabled. Retain this failure finding; do not weaken the runtime assertion.

Pinned runtime SHA-256: `fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e`. Use 127.0.0.1 only, a reserved unused backend port, a 256 MiB heap and `cloud.enabled: false`. No real players, API providers or statistics endpoint are contacted. The operational driver `tools/qualify_velocity_bstats.py` binds the artifact/addon/source/log hashes in its receipt. HTTP submission/production ingestion remains unverified.
