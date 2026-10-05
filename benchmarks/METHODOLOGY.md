# Measurement contract

## Questions and layers

1. Does a plugin deny a controlled positive and admit a controlled negative, including IPv6 and IPv4-mapped IPv6?
2. Does its configured open/closed failure policy work for HTTP 503/429, deadline expiry, malformed JSON and missing fields?
3. Do manual deny/allow, conflict precedence and temporary expiry avoid unnecessary provider calls?
4. Do geo blocklists, allowlists and an empty allowlist obey the declared policy?
5. Do a warm cache and simultaneous same-IP requests reduce source work? Can distinct-IP requests finish under the fixed concurrency controls?

The 25-case datasets hold explicit expected results. Expectations express the stated test contract, not a claim that each competitor offers every feature. `unsupported` means this adapter has no qualified equivalent. `error` means a setup, compatibility or isolation problem prevents judging that case. `fail` means a complete qualified measurement violates its decision or source-request contract. Separate these statuses; never score missing adapters as inaccurate or infinitely slow.

**core_lookup** calls the unmodified CG JAR's actual lookup service through typed synthetic source futures. It measures its normalization/deadline/coalescing path and cannot prove HTTP parsing, country accuracy or a native kick.

**native_velocity_login_gate** loads an unmodified plugin alone in an owned proxy. A small protocol-760 (Minecraft 1.19.2) client presents a fixed synthetic subject through PROXY v2. Login Success is ALLOW; pre-success disconnect is DENY. No backend is joined and no real Minecraft identity is authenticated. The native guard and dependencies are instrumentation and can affect absolute timings. Full Paper/Folia/Bungee/Geyser/native transport/TPS and real-user performance are outside v1.

## Fixture and product configuration

- CG and GeoRestrict use the same local HTTP response/delay fixture, 500 ms HTTP timeout and four lookup workers; the GeoRestrict gateway schema and CG custom-provider schema both receive the actual documented fields. CG lookup deadline is 750 ms; GeoRestrict has its own lifecycle.
- Cold CG cases disable cache. GeoRestrict has no demonstrated cache-off equivalent; the real `georestrict purgecache` command runs before every cold batch, outside the measured interval. Cold entry state is equivalent; storage and configuration mechanisms differ. The warm-cache case intentionally retains the cache after warmup.
- CG geo uses a real local MMDB import with an explicit fixture build timestamp. GeoRestrict uses the controlled HTTP country response. Their geo latencies describe different source paths and should not be read as a pure plugin-speed comparison.
- Sqidgeon receives locally generated CIDR files with update/download and reverse DNS disabled. Its profile is `controlled_local_cidr_and_manual_rules`, separate from the HTTP profile. Zero HTTP calls for a local CIDR match are expected and do not establish better live detection or performance than an HTTP lookup.
- Rules run through actual console commands. An ABI error in the exact binary is a runtime-compatibility qualification problem, not a guessed feature absence.
- Each case/round gets a fresh JVM and data directory. Warmups are retained but excluded from percentiles. Expiry additionally proves an initial denial before waiting for the rule deadline.

## Evidence and timing

Receipts bind artifact, runtime, suite, adapter source files, configuration seeds, dependencies, Java/OS/CPU/RAM/Python versions and log hashes. Sequential matrix rounds rotate product order and alternate case order. A local advisory lock prevents competing cgbench jobs; unrelated workloads can still distort timing. Log host load and disclose it.

Each batch records each client latency, total batch elapsed time, observed HTTP fixture calls, and whole-proxy RSS/CPU observations before/after the batch. The request counter measures HTTP fixture calls only; local CIDR/MMDB reads are not instrumented. HTTP calls belong to a batch and are never multiplied by the number of concurrent clients. RSS is not peak memory or plugin allocation. `ps` CPU accounting is coarse and includes unrelated proxy work; it is unsuitable for a claim about plugin CPU cost alone.

Functional qualification defaults to 3 rounds × 4 measured batches with 2 warmup batches. That is **too few independent rounds/tail observations for a credible p99 or general speed ranking**. Percentiles include failed/time-out observations; dropping slow failures would create survivor bias. The comparator only admits complete, oracle-correct, request-budget-correct cases without fixture/permission problems and with matching dataset, environment, adapter, layer, profile, cache contract, sampling and matrix schedule. Matching wrong outcomes cannot win. Its interval resamples independent round medians, not repeated clients; 3 rounds remains exploratory. The interval is an estimate for this fixture, not a universal ranking or production confidence statement.

For a performance study, predeclare representative cases and an improvement target, use at least 5 independent rounds and 30+ measured batches with 20+ warmups per case (within bounded row limits), interleave products, inspect variance/load and repeat on a separate otherwise-idle host. Do not infer tail confidence merely from a percentile function or pretend samples from one JVM are independent. No timing claim is published from the first small functional run.

## Connection Guard improvement loop

Keep exact receipts and a dated backlog. Reproduce a failed oracle or repeated bottleneck before changing production code. Make one scoped change on a new isolated branch; compare stable and candidate unmodified JARs under the same matrix conditions. A fix must pass its functional case and the existing core/native checks. A performance improvement must preserve decisions, failure-policy behavior and source budgets, with an independently repeated effect. Use the measured tradeoff (latency, source calls, whole-process resources), not a single combined score.

The real endpoint study remains separate. Synthetic VPN/country responses cannot establish detection rates, false positives, market leadership, residential-proxy coverage or safety for mobile/CGNAT users. No reviews, stars, customer references or blocking percentages are invented.

## Additive retained-cache failure/recovery study

`failure-recovery-v1.json` has eight cases: paired OPEN/CLOSED HTTP429, malformed JSON and missing-field cases, plus genuine positive and negative cache controls. A native failure case retains SQLite (CG) or the product's configured cache (GeoRestrict); no purge, reload, cache replacement, source replacement object or new JVM occurs between its phases. Only the owned HTTP fixture response switches from a failure to a valid positive. The existing v1 cold-cache and first baseline files remain unchanged.

A failure must first exercise the owned source. The four measured login batches must obey OPEN/ALLOW or CLOSED/DENY. After recovery, at least one actual positive source call and a DENY are required, followed by a DENY with zero HTTP calls. A cached false negative would hide that recovery; a CLOSED denial by itself would also be insufficient evidence. Real positive/negative warm-cache controls must pass with zero measured calls after warmup. This is an observable cache-recovery contract; it does not inspect a competitor's internal typed vote or establish live detection accuracy.

429 responses explicitly carry `Retry-After: 2`. After the measured failure batches, the source becomes positive; three immediate logins within the advertised window must retain the original OPEN/CLOSED outcome and make zero source requests. After that window plus a small scheduling margin, positive recovery and cache replay are checked. A fixture that misses the window is an error, not evidence against the plugin. This checks this adapter/configuration's response to this header; it is not a provider-account quota guarantee.

Core measurements remain separate: six typed failure cases must be UNKNOWN with RATE_LIMIT (or CIRCUIT_OPEN on a paused repeated lookup) or INVALID_RESPONSE as declared. This uses owned typed futures and a disabled cache; actual parsing/storage/recovery is qualified only by native receipts. Recovery and pause probes are recorded in `recovery_proofs`, outside the measured timing sample; product-contract failures and incomplete proofs disqualify a case from latency comparisons. No general timing claim is made from this small functional study.
