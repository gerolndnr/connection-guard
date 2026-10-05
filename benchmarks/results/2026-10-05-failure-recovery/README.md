# Retained-cache failure/recovery qualification — 5 October 2026

An additive study after the [first baseline](../2026-10-05/README.md), on the same exact unmodified published JARs: Connection Guard **0.5.0** and GeoRestrict **2.0.2**, Velocity 3.4.0 build 566, Java 21, owned loopback HTTP and synthetic protocol 760 clients. The original 25-case datasets and baseline remain unchanged. [Dataset](suite.json), [schedule](schedule.json), [native report](native/report.md), [CSV](native/results.csv) and raw receipts are included.

| Product | Complete cases passed | Complete contracts failed | Fixture errors | Measured login observations |
| --- | ---: | ---: | ---: | ---: |
| Connection Guard 0.5.0 | 8 | 0 | 0 | 96 |
| GeoRestrict 2.0.2 | 6 | 2 | 0 | 96 |

Three rotated rounds, four measured batches and two warmups per case: 192 measured logins, 96 warmup logins and 108 extra recovery/pause probes; 48 owned proxies, all exit 0, no denied fixture permissions. Extra probes are separate from latency samples. The tiny functional sample is not a product speed ranking.

## What is demonstrated

- Paired OPEN/ALLOW and CLOSED/DENY for 429, malformed JSON and missing fields pass for both products. The three previously absent CLOSED cases are now exercised through actual native login gates.
- Actual caches remain enabled and untouched across failures, source recovery and replay: SQLite for CG, GeoRestrict's configured cache. Both products request the recovered positive, deny it and reuse that positive with zero further HTTP calls. Genuine positive and negative warm-cache controls also pass with zero measured calls. These failure responses do not hide a later positive behind a cached negative in this tested configuration.
- For `Retry-After: 2`, CG makes one failure-source request per case/round, preserves the configured OPEN/CLOSED decision for three immediate probes with zero calls, then requests the recovered positive after the window and caches it. GeoRestrict makes six failure-source calls across the two warmups/four measured batches, then requests the now-positive source during the advertised pause. This violates the declared pause contract in both 429 cases in all three rounds. Its failure policy and later positive cache reuse still pass. This is exact artifact/configuration evidence, not an account-wide quota or general security claim.
- A separate [CG core receipt](connection-guard-core.json) has 72 measured typed-source observations: six UNKNOWN/source-reason cases pass; two native cache controls are unsupported at that layer. RATE_LIMIT and INVALID_RESPONSE remain distinguishable from NEGATIVE. Core checks do not exercise parsing or SQLite.

## Evidence and limits

[CG native receipt](connection-guard-native.json), [GeoRestrict native receipt](georestrict-native.json), [CG summary](connection-guard-summary.json), [GeoRestrict summary](georestrict-summary.json). Each binds exact JAR, runtime, fixture source, configuration seeds, dependency and environment hashes. Native recovery evidence is under `recovery_proofs`; it records actual outcome/request counts, not a simulated vote. Console/config/cache data stay private.

No production code, stable version, real endpoint labels, public provider queries, backend joins or account authentication changed. No Paper/Folia/Bungee, native forwarding, TPS, country/detection accuracy or real-world false-block claim follows. All actual TCP destinations remain loopback; cooperative guard scope is unchanged. Sqidgeon's local-CIDR profile and unqualified external/hosted competitors are outside this HTTP/cache study.

CI now runs six additional UNKNOWN/source-reason cases against the actual freshly built CG JAR, alongside the original 17 core cases and 49 offline benchmark contracts. Native receipts qualify the exact stable 0.5.0 artifact; CI's development JAR has a separate hash and does not inherit that native qualification automatically. Next: [bounded improvement action](BACKLOG.md).
