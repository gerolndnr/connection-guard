# First controlled comparison — 5 October 2026

**A working baseline for improvement, not a real-world detection ranking.** The native run uses three unmodified published JARs, 25 declared cases, three rotated rounds, four measured batches and two warmup batches per supported case. There are 1,704 measured synthetic login observations, separate from 564 typed-source core observations. Raw receipts include warmups and visible unsupported/setup outcomes.

| Native artifact | Passed cases | Contract failures | Unsupported | Compatibility/setup errors |
| --- | ---: | ---: | ---: | ---: |
| [Connection Guard 0.5.0](connection-guard.json) | 24 | 0 | 1 | 0 |
| [GeoRestrict 2.0.2](georestrict.json) | 19 | 1 | 5 | 0 |
| [Sqidgeon Anti-VPN 1.0.0V; binary 1.0.0](sqidgeon-antivpn.json) | 8 | 0 | 15 | 2 |

The single CG unsupported native case asks for a typed `GB` value, which a login packet cannot return; three actual geo enforcement cases pass. GeoRestrict's four manual-rule cases are not implemented by this adapter, and its typed country-value case is likewise unsupported. Sqidgeon has a qualified local-CIDR profile for IPv4/IPv6/mapped-address and concurrency checks, not an HTTP/geo equivalent. Its two manual-rule commands trigger `AbstractMethodError` on this exact Velocity build and binary; they are compatibility errors, not accuracy failures or claims about every version.

## Findings that matter for Connection Guard

- **Preserve same-IP coalescing.** Across all 12 measured 16-client batches, CG sends one HTTP fixture request; GeoRestrict sends four. All clients are denied in both products. GeoRestrict's one contract failure is the suite's explicit ≤1-request efficiency target, not an incorrect deny decision or a claim that its author promised this feature.
- **Preserve warm-cache behavior.** Both HTTP-profile products deny the cached positive with zero measured fixture requests. CG uses SQLite; GeoRestrict uses its native cache. Storage semantics differ and are part of the tradeoff.
- **Preserve native 429 backoff and failure policy.** CG exercises the 429 fixture during warmup, then suppresses further calls during its retry pause while allowing under OPEN. GeoRestrict makes one request per measured batch and also allows. No-query retries here are legitimate backoff, not proof of a broken source. 503 and timeout OPEN/CLOSED cases pass for both products.
- **Investigate CG's fixed overhead before optimizing it.** In this small run, cold IPv4-positive p50 is 3.14 ms for CG and 2.40 ms for GeoRestrict; warm-cache p50 is 2.34 vs 1.69 ms. These are exploratory observations under instrumentation and background host load, not a demonstrated production advantage. The next experiment must use more independent rounds, otherwise-idle hardware and preserve storage/decision semantics.
- **Do not claim a local-list speed win over HTTP.** Sqidgeon's owned CIDR matches make zero HTTP calls by design. The comparator excludes its profile from HTTP ranking. CG's MMDB geo and GeoRestrict's HTTP geo are likewise different per-case source conditions.

[Prioritized improvement backlog](BACKLOG.md) ties each next action to evidence, a concrete experiment and acceptance criteria.

## Evidence and reproduction

- [Native report with per-case coverage, timings and observed whole-proxy RSS](report.md).
- [CSV](results.csv), [exact native dataset](suite.json), [rotated schedule](schedule.json), [qualified paired HTTP comparisons](paired-http-comparison.json). Only 16 case comparisons qualify; all timing intervals remain exploratory.
- [CG typed-source core receipt](core-connection-guard.json), [separate core report](core/report.md), [core CSV](core/results.csv): 17 cases pass, 8 have no core-layer equivalent. Core outcomes cannot prove native enforcement or HTTP parsing.
- Each native JSON binds all runner inputs, configuration seeds, artifact/runtime/dependency hashes, Java/OS/CPU/RAM/Python versions and private log hashes. No denied fixture permissions occurred in the final native matrix. All 162 owned JVMs stopped; shutdown results are in the receipts.
- [Runner and commands](../../README.md), [measurement contract](../../METHODOLOGY.md), [target/version/license inventory](../../targets.json).

Host: Apple M3, 8 logical CPUs, 8 GiB RAM, macOS Darwin 25.2.0, Homebrew OpenJDK 21.0.9, Python 3.14.0, PyYAML 6.0.3. Velocity 3.4.0 build 566 uses loopback NIO and protocol-760 synthetic clients, 64/256 MiB heap and two event-loop threads. Background load is recorded and prevents general speed claims. RSS covers the whole proxy after batches, not peak usage or plugin allocation.

The CG baseline is the **published 0.5.0 artifact**, SHA-256 `172d6e4dc7b925efc8498cab46f28807dba47f20750fbfbdb3c56a64002bb503`. Development master contains the subsequent Velocity bStats change; the CI core gate checks its freshly built JAR separately. This benchmark does not prove bStats ingestion because telemetry is disabled. Sqidgeon's required external SQLite JDBC 3.46.0.0 dependency is explicitly recorded. Country fixtures use MaxMind's attributed artificial test database, not a production accuracy sample.

## Gaps and excluded attempts

Topaz is unqualified: its hardcoded external HTTPS provider could not be redirected through a demonstrated safe local fixture. Automatic approval review rejected the proposed external URL/transport exception; the exception was removed and no such execution is part of these results. LXVPN's inspected repository artifact does not match the source's advertised BETA version. Both remain visible in the target inventory without invented outcomes. Other backend/hosted/licensed candidates need their own exact artifacts and qualified adapters.

Earlier private attempts exposed missing fixture permissions, a command-marker error, misclassification of 429 retry suppression, and unclosed process pipes during a long run. They were corrected and superseded by the complete final matrix. They are not competitor detection failures. Competitor JARs, database files, dependency JARs and raw logs are not redistributed.

**Not measured:** independent VPN/non-VPN detection rates or false-block rates, country accuracy, residential-proxy coverage, real-account authentication, backend join/TPS, Paper/Folia/Bungee/Geyser comparisons, production overhead, default Cloud networking, or hosted-service quotas. [Verified-endpoint study protocol](../../VERIFIED_ENDPOINTS.md) exists but contains no collected live endpoint labels.
