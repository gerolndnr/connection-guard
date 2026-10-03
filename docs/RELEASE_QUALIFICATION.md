# Release qualification after the bStats incident

The 0.4.10 Bukkit adapter loaded bstats-bukkit through Libby without its Maven
transitive bstats-base dependency. Core unit tests and a Velocity-only runtime
fixture did not execute the failing Bukkit enable path. A green build was
therefore insufficient release evidence. Version 0.4.11 bundles and relocates
the missing base dependency and passed a real Paper enable/reload/player-join test.

## Enforced checks

- The combined JAR must contain exactly one relocated MetricsBase and
  JsonObjectBuilder class, the dependency notice, consistent platform versions,
  expected bytecode, and no bundled server API. This check is unconditional.
- `python3 ci/test_release_gate.py` removes and duplicates both bStats classes
  in copies of the actual built JAR. Each broken copy must be rejected. It also
  checks missing platforms, stale artifact/source/version, failed startup/reload/
  shutdown, changed logs, wrong runtimes and escaping evidence paths.
- The Build workflow starts the exact combined JAR alone on pinned Velocity
  3.4.0 build 566 / Java 21 in a new directory. It executes `cg help`, `cg reload`
  and shutdown; startup/linkage errors and timeouts fail the job. Logs survive
  failed jobs. Repeatable archive and real Redis regression checks remain active.
- Before a release, repeat the startup fixture on pinned Paper 1.21.11 build 132
  / Java 21 after an explicit EULA decision. The fixture uses loopback, at most
  768 MiB, a tiny synthetic offline world, disabled telemetry and no other plugins.
  EULA acceptance is deliberately not implicit in PR CI.
- The operations publication helper uses `ci/release_gate.py` before any GitHub,
  Modrinth or Hangar publication. Both Paper and Velocity evidence must match the
  release JAR SHA-256, version, source commit, official runtime hashes and retained
  console-log hashes. Startup, commands, reload and clean stop must all pass.
  The source checkout must be clean, packaging must pass again, and the exact
  source commit must have a successful Build run on its qualified branch.
- The downloaded public artifact is checked against the qualified checksum.
  Spigot points to that verified GitHub artifact; run the same gate before its
  manual browser update. Version tags/assets are immutable: a fix uses a new version.

Example from a clean, committed checkout (Paper requires a conscious decision):

```sh
python3 ci/startup_smoke.py --platform paper --accept-eula \
  --artifact build/libs/connection-guard-VERSION-all.jar \
  --java /path/to/java21 --work-dir /path/to/new-paper-fixture
python3 ci/startup_smoke.py --platform velocity \
  --artifact build/libs/connection-guard-VERSION-all.jar \
  --java /path/to/java21 --work-dir /path/to/new-velocity-fixture
```

In the operations manifest, `startup_evidence` maps `paper` and `velocity` to the
two result files relative to the operations workspace. Keep each `console.log`
beside its result. `release_operations.py gate status --version VERSION` checks
qualification without a publication. A fresh build with another hash requires
fresh evidence; counts or prose in a checklist cannot substitute for it.

## Limits and further coverage

These fixtures prove clean enable, command registration, reload and shutdown on
the two pinned runtime versions. They do not prove authenticated player identity,
all supported Minecraft versions, BungeeCord, Folia, provider accuracy or every
integration. Keep separate login/policy/cache/failure/scheduler tests for changed
behavior, and qualify additional advertised platforms before the next feature
release. Do not turn a startup smoke result into a broader compatibility claim.

The helper blocks the normal delivery path; a repository administrator can still
bypass it manually. Remote branch protection is not implied by a workflow file.
Absolute defect freedom is impossible. The concrete incident now has automated
negative coverage and an actual Bukkit lifecycle check before publication.
