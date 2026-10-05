# Connection Guard comparative benchmark suite

This suite compares **unmodified published JARs** through controlled, reproducible cases. Use the findings to choose Connection Guard improvements; there is no combined marketing score.

The first native adapters are Connection Guard, GeoRestrict and Sqidgeon Anti-VPN on **Velocity 3.4.0 build 566 / Java 21**. [Target inventory](targets.json) records other competitors and qualification gaps. [Methodology](METHODOLOGY.md) explains what each measurement means. [First results](results/2026-10-05/README.md) include the raw controlled receipts, CSV and an improvement backlog.

## Run

Python 3.12+ and a Java 21 JDK are required for the native fixture. No accounts, paid services, real players or Minecraft backend are required. Install the two pinned Python dependencies in your own environment:

```sh
cd benchmarks
python3 -m pip install -r requirements.txt
CGBENCH_JAVA=/absolute/path/to/jdk21/bin/java python3 -m unittest discover -s tests -v
python3 -m cgbench.cli plan --suite datasets/controlled-native-v1.json
```

Download only a free public artifact whose license permits your intended use. Keep it private; this repository redistributes no competitor JARs. The fetch command selects the latest public stable release explicitly declaring Velocity and the supplied game version, verifies the official SHA-512 and records the actual descriptor/version:

```sh
python3 -m cgbench.cli fetch-modrinth georestrict --game-version 1.21.11 --destination artifacts/georestrict
python3 -m cgbench.cli fetch-modrinth anti-vpn --game-version 1.21.11 --destination artifacts/sqidgeon
```

Inspect `receipt.json` and the target license before execution. A declared game version does not prove runtime compatibility. Match the source/configuration/commands to that exact platform JAR. Never substitute a historical or different-platform artifact for an unavailable version.

The pinned official Velocity download is [build 566](https://fill-data.papermc.io/v1/objects/fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e/velocity-3.4.0-566.jar); the runner verifies SHA-256 `fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e`.

Copy [matrix.example.json](matrix.example.json), replace its absolute paths, then run:

```sh
python3 -m cgbench.cli run-matrix --manifest results-private/matrix.json --work results-private/run-001
python3 -m cgbench.cli compare results-private/run-001/connection-guard.json results-private/run-001/georestrict.json --suite datasets/controlled-native-v1.json
```

The matrix runs one owned proxy at a time, rotates product order between rounds, alternates case order, and refuses a concurrent cgbench run. An existing output directory is never overwritten. All actual TCP targets are loopback. Subject IP literals occur only in synthetic PROXY-v2 headers and owned fixture requests. Cloud, bStats, update checks and actions are disabled. Java 21's cooperative guard denies external transport and audits denied fixture permissions; this is **not a sandbox for hostile code**. Only run artifacts you have reviewed and trust. DNS metadata resolution remains allowed for runtime interface enumeration.

Connection Guard needs its prefilled `plugins/connection-guard/lib` dependency cache, copied into each fixture. Native geo cases additionally require the explicit [MaxMind public test database](https://github.com/maxmind/MaxMind-DB/tree/main/test-data), MIT attribution, SHA-256 `b37601903448683d241af52893c8cbf0fed461e0cdebe0bfaca01891fdeb6db9`, build timestamp `2026-02-04T22:49:29Z`. This tiny artificial database supplies a deterministic GB fact; it does not measure country accuracy. Sqidgeon's Velocity binary needs an explicit SQLite JDBC dependency on the parent classpath; its hash is recorded. The first run used SQLite JDBC 3.46.0.0.

To test the CG lookup service without a proxy, HTTP parser or third-party artifact:

```sh
python3 -m cgbench.cli run-core --artifact /absolute/path/connection-guard-all.jar --java /absolute/path/to/jdk21/bin/java --work results-private/core-001
```

Core and native results are separate layers and cannot be ranked against each other. CI runs the offline suite contracts and the CG core fixture against the newly built JAR. Competitor native evaluations remain explicit local jobs with reviewed artifacts.

## Outputs and extension

Each run produces a hash-bound receipt and coverage summary. A matrix also writes its schedule and Markdown/CSV export. Export a completed receipt again without running a server:

```sh
python3 -m cgbench.cli export results-private/run-001/connection-guard.json results-private/run-001/georestrict.json --suite datasets/controlled-native-v1.json --output results-private/export-001
```

Add an adapter only after positive and negative source controls pass, exact configuration and commands are verified, external transport is prevented, and fixture setup errors are distinguished from decisions. Unsupported cases stay visible. Native adapters must exercise the real enforcement path, not implement the competitor's decision logic inside this suite. See [methodology and acceptance criteria](METHODOLOGY.md).

Production detection and false-block rates require independently verified, owner-controlled endpoints. [Collection protocol](VERIFIED_ENDPOINTS.md) and [empty input template](datasets/verified-endpoints-template.json) deliberately contain no invented labels. `python3 -m cgbench.cli accuracy --observations <private-json>` aggregates supplied verified observations; it does not acquire endpoints or independently certify an attestation.
