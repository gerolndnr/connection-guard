# Preregistered controlled latency study v1

Status: protocol and preflight delivered; **no timing study or optimization result yet**.
The exploratory first baseline was too small and shared the machine with other work.
Freeze this plan before measuring or choosing a production optimization. Preserve it
and every failed/partial attempt; a changed target, artifact or sampling rule needs
a separately identified new plan. Earlier published results remain unchanged.

## Question and fixed endpoint

Does a reviewed CG candidate reduce the round-level p95 client login refusal latency
of `vpn_positive_v4`, while preserving decisions, source budgets and cache behavior?
Compare stable CG 0.5.0 and a separately hash-pinned candidate with the same CG adapter
and byte-identical dependency caches. GeoRestrict 2.0.2 is a fixed descriptive HTTP
comparator, not the acceptance baseline. Its different source/parser/cache path is
visible; a plugin-wide or real-provider speed ranking is not justified.

The [four-case dataset](../datasets/latency-v1.json) contains cold positive,
200 ms delayed positive, warm positive SQLite cache and 16 distinct concurrent IPs.
Cold means lookup-cache cold: CG storage disabled, GeoRestrict cache purged outside
the timed region. JVM/library warmups are still performed. Source requests must equal
1, 1, 0 and 16 respectively per measured batch, and every decision must be DENY.
The 16 callers in a batch are correlated, not 16 independent experimental units.

## Design and acceptance rule

The [machine protocol](latency-v1.json) requires six rounds, 30 measured batches and
20 warmup batches **per case/product/round**. Sequential product rotation balances
the three starting positions twice; case order alternates. There are 72 owned JVMs,
10,260 measured logins and 6,840 warmup logins. The primary analysis unit is a round.
Compute p95 over its measured client durations using the suite's linear-interpolated
quantile. Do not discard high durations or retain only favorable rounds/cases.

For each round, reduction is `1 - candidate_p95 / stable_p95`. A candidate is eligible
only if all of these hold:

- All cases, warmups, exact batch IDs/counts, source budgets, clean process exits,
  transport audits and receipts are complete; no errors, timeouts or changed conditions.
- Mean primary reduction is at least 20%, with at least five of six rounds showing
  at least 20%. Resample **round reductions**, 2,000 replicates, seed 20261005;
  the 95% percentile interval's lower bound must be above zero. Six rounds give a
  limited interval; this is a local acceptance rule, not a statistical power claim.
- Each secondary case has no mean round-p95 regression above 10%. SQLite remains
  enabled for warm cache, with zero measured upstream calls. Storage changes require
  a new explicit tradeoff study, not a hidden faster profile.
- The candidate passes existing plugin regression, packaging, 23 actual-JAR core
  gates and the native failure/recovery contracts. The four timing cases alone cannot
  authorize a release or establish policy correctness.
- Repeat the **entire** frozen study on a separate idle session, preserving both
  studies. Both must satisfy the rule before choosing the optimization.

Reporting/acceptance is a reviewed calculation from raw receipts for now; the existing
`compare` command reports round **medians**, so it does **not** implement this p95 rule.
No automatic optimization approval or statistical conclusion is implemented here.

## Prepare and run later

Copy `matrix.example.json` into private storage. Set `suite` to `datasets/latency-v1.json`,
add `study` pointing to `studies/latency-v1.json`, and use the exact three IDs/order
`cg-stable`, `cg-candidate`, `georestrict` with adapters and pinned hashes from the
protocol. Every product needs `artifact_sha256`; candidate's actual hash is mandatory
in the manifest. Both CG entries need explicit matching `assets` directories.
Set rounds/samples/warmups to 6/30/20 and actual absolute Java/runtime/artifact paths.

```sh
python3 -m cgbench.cli plan-study --manifest /private/path/matrix.json --work /private/path/study-001
python3 -m cgbench.cli run-matrix --manifest /private/path/matrix.json --work /private/path/study-001
```

Preflight verifies dataset, artifacts, dependencies, runtime, sampling and product
identity **before output or proxy creation**. It requires 6 GiB free and both 1-/5-minute
host load averages ≤0.25 per logical CPU. It never deletes old evidence, stops other
agents' work or starts a proxy from `plan-study`. `run-matrix` repeats preflight under
the suite lock, saves `study-plan.json` before native work, and binds each receipt to
that plan's hash. Unmarked matrices remain exploratory functional comparisons.

Coordinate a quiet session with the other agent/operator before timing. Start-load
preflight is **not continuous idle verification**: retain independent host-load/process
observations throughout the run and declare competing work/interruptions. Abort or
mark the whole session unqualified if conditions change; do not cherry-pick the clean
tail. Continuous host monitoring and an automated p95 acceptance reviewer are still
future suite work. No run may be advertised as an idle-host study solely because its
start preflight passed. No new EULA scope, live IP probes, payments or outreach.
