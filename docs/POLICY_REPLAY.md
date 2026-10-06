# Local policy replay

`/cg policy test` evaluates eight bundled **synthetic** cases against the active policy. Permission: `connectionguard.command.policy`. It uses the same final VPN/Geo rule evaluator as Bukkit, BungeeCord and Velocity.

To compare a complete candidate, create a `policy` directory inside the plugin's data directory, copy [candidate.json](../ci/fixtures/policy/candidate.json) there, review it, then run:

```text
/cg policy test examples candidate
```

Custom saved cases: copy [examples.json](../ci/fixtures/policy/examples.json) into `policy/my-cases.json` and edit the synthetic examples. Run `/cg policy test my-cases candidate`. Names accept letters, digits, underscores and hyphens only; no `.json` suffix, paths or symlinks. Input is limited to 256 KiB, 64 cases, 16 VPN sources per case and 1,024 rules. Unknown fields, duplicate keys/IDs, coerced booleans/numbers and unsupported enum values are rejected before any comparison is reported.

The output counts changed effective refusal reasons, changed flags and changed selected rules separately. It shows case IDs, source status/quorum, selected rules, evaluation time, capture time and age. A single-case input also shows source versions/expiry and bounded metadata-rule match traces. It does not show the case's IP or UUID.

This command reads saved evidence. It makes **no provider requests**, changes no cache namespace, activates no candidate, sends no decision event/webhook, and executes no staff notifications, console commands or kicks. Existing players and live policy stay unchanged. A candidate contains the complete policy portion (mode, failure policies, kick choices, geo list and access rules); `rules: []` explicitly means no access rules in that candidate. It is not a partial overlay.

Saved evidence does not prove today's provider accuracy. Replay does not reproduce provider/threshold configuration changes, fresh identity or LuckPerms resolution, overload refusal, admission hooks, ban/challenge outcomes or real account authentication. Exemption and identity fields are explicit **synthetic test assumptions**. Old source facts expire at the same boundary as live facts. The captured VPN quorum is preserved; unknown sources never silently reduce it. Source hashes identify saved data, not a current provider version. Changed decisions are **not** a false-positive rate.

This feature is in development (`0.6.0`), absent from stable `0.5.1`. No saved live observation capture is enabled. [Separately authorized version activation and rollback](POLICY_VERSIONING.md) are under qualification. Explicit saved observations remain unfinished; replay and the bounded shadow comparison below must not be presented as the complete policy rollout workflow.

## Live shadow comparison

After reviewing `policy/candidate.json`, enable a comparison explicitly:

```text
/cg policy shadow start candidate 15m
/cg policy shadow status
/cg policy shadow stop
```

The same `connectionguard.command.policy` permission applies. Supported windows are `5m`, `15m` (default) and `1h`. There is no automatic start, scheduler, upload or on-disk observation log. A candidate is immutable for the session; editing its file does not silently change a running comparison. At most 128 candidate rules, 10,000 reserved samples and eight simultaneous comparisons are allowed. Busy samples are skipped and counted, rather than queued. Work already reserved can finish after stop; `pending` exposes this. One active window is allowed; starting a replacement requires stopping the current window first.

At the final policy evaluator, the candidate consumes the **same** existing VPN/geo facts, evaluation timestamp and resolved identity/exemption inputs as the live policy. It does not query any provider, fetch missing facts, use a candidate cache namespace, send a second observer/Cloud event, execute commands or kick players. Candidate failure ends the comparison and increments a redacted error count; the actual live result is returned unchanged. Counters remain in memory until a new window or restart. Observed IPs, UUIDs, names and provider response bodies are not retained by the shadow session. Configured candidate rules may contain operator-supplied IP/UUID targets just as ordinary access rules do.

Status identifies the two decision policies with SHA-256 fingerprints and reports changed effective refusal reasons, flags and selected rules; unknown VPN/geo facts, busy skips, pending work, errors and candidate evaluation time are separate. The fingerprint covers decision settings and ordered access rules, including expiry. It excludes provider credentials and operational settings; the session separately checks the current configuration generation and immutable rule snapshot. A successful reload, provider/threshold/cache change, manual or Cloud rule change ends the session as `BASE_CHANGED`, retaining the old counters. Even an idle session expires at its monotonic deadline on status read. Invalid drafts rejected before activation leave the old comparison and live settings active.

This is a sample of calls that reach the **final policy evaluator**, not every login. Earlier admission, ban/challenge, literal-rule refusals and skipped lookups may be absent. Missing/exempt facts stay missing; a candidate cannot undo an earlier exemption resolution or reconstruct a lookup that did not run. The window does not freeze remote provider behavior or refresh local data; new completed source facts are used equally by both policies. It does not replay actions already performed elsewhere in the pipeline. Changed outcomes are **not false positives**, a detection rate or a rollout approval. No activate/rollback command is provided in this package.

## JSON contracts (schema 1)

The linked files are executable examples. Every listed property is typed. `captured_at`, `cached_at`, `valid_until` and rule `expires_at` are nonnegative epoch milliseconds; `0` means unavailable capture/cache age or permanent/no source expiry respectively. Source versions are lowercase SHA-256 or null. Durations are milliseconds, at most 3,600,000. A dataset must declare `kind: "synthetic"`; live observations are rejected.

VPN aggregate status is derived from the saved voting source facts and captured `threshold` (1–16). Enrichment-only sources do not vote. Source status accepts `POSITIVE`, `NEGATIVE`, `UNKNOWN`; only `UNKNOWN` may have a non-`NONE` failure reason. Geo has either a two-letter uppercase country with reason `NONE`, or null country/ISP/ASN with a non-`NONE` reason. Classification fields, ASN, provider-specific decimal risk and confidence are optional observations; absent values stay unknown.

Rule effects: `DENY`, `ALLOW`, `EXEMPT`; scopes: `VPN`, `GEO`, `ALL`. Targets follow [access-rule syntax and precedence](ACCESS_RULES.md), including literal IP/CIDR, canonical UUID, exact metadata and source-specific risk thresholds. UUID requires a synthetic trusted identity. A literal DENY wins even when a permission exemption was resolved before that deny was added; metadata rules keep existing permission-exemption behavior.
