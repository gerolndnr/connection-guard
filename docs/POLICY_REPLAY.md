# Local policy replay

`/cg policy test` evaluates bundled **synthetic** cases or an explicitly saved local observation sample against the active policy. Permission: `connectionguard.command.policy`. It uses the same final VPN/Geo rule evaluator as Bukkit, BungeeCord and Velocity.

To compare a complete candidate, create a `policy` directory inside the plugin's data directory, copy [candidate.json](../ci/fixtures/policy/candidate.json) there, review it, then run:

```text
/cg policy test examples candidate
```

Custom saved cases: copy [examples.json](../ci/fixtures/policy/examples.json) into `policy/my-cases.json` and edit the synthetic examples. Run `/cg policy test my-cases candidate`. Names accept letters, digits, underscores and hyphens only; no `.json` suffix, paths or symlinks. Input is limited to 256 KiB, 64 cases, 16 VPN sources per case and 1,024 rules. Unknown fields, duplicate keys/IDs, coerced booleans/numbers and unsupported enum values are rejected before any comparison is reported.

The output counts changed effective refusal reasons, changed flags and changed selected rules separately. It shows case IDs, source status/quorum, selected rules, evaluation time, capture time and age. A single-case input also shows source versions/expiry and bounded metadata-rule match traces. It does not show the case's IP or UUID.

This command reads saved evidence. It makes **no provider requests**, changes no cache namespace, activates no candidate, sends no decision event/webhook, and executes no staff notifications, console commands or kicks. Existing players and live policy stay unchanged. A candidate contains the complete policy portion (mode, failure policies, kick choices, geo list and access rules); `rules: []` explicitly means no access rules in that candidate. It is not a partial overlay.

Saved evidence does not prove today's provider accuracy. Replay does not reproduce provider/threshold configuration changes, fresh identity or LuckPerms resolution, overload refusal, admission hooks, ban/challenge outcomes or real account authentication. Synthetic files declare identity/exemption assumptions; saved local cases preserve the resolved inputs within their disclosed capture mode. Old source facts expire at the same boundary as live facts. The captured VPN quorum is preserved; unknown sources never silently reduce it. Source hashes identify saved data, not a current provider version. Changed decisions are **not** a false-positive rate.

## Explicit local observation capture (unreleased 0.6.1)

Capture is **off by default**. An operator with `connectionguard.command.policy` and `connectionguard.command.policy.capture` starts a finite window:

```text
/cg policy capture start 15m facts
/cg policy capture status
/cg policy capture stop
/cg policy test observations candidate now
/cg policy test observations candidate captured
/cg policy capture clear
```

Windows are `5m`, `15m` (default) or `1h`. The default `facts` mode replaces real IPs and UUIDs with fixed documentation placeholders, while preserving the resolved trust and scoped exemption booleans. Ordinary authenticated logins are included. IP/CIDR and UUID rules cannot be evaluated against those placeholders: facts capture skips evaluations when such rules are configured, and replay rejects any current or candidate policy containing them.

To simulate literal address or identity rules, choose `/cg policy capture start 15m identities` explicitly. This private local sample contains the normalized connection IP and available UUID, along with the **captured** trust/exemption inputs. It never contains player names, city names, URLs, raw provider response bodies or provider credentials. The start command discloses the selected identity retention, and status shows the mode. This is local personal data; keep the file private, reflect this optional processing in your server notice and delete it when finished. Spigot defaults both permissions to operators; proxy permission systems must grant them.

Only calls reaching the shared final VPN/Geo policy evaluator are sampled. Earlier refusals, skipped lookups, external admission/challenge/ban decisions and actual account authentication are not reconstructed. Each case copies the existing source status, failure reasons, quorum, aggregation strategy, metadata, cache time, source version and expiry. The original provider failure remains UNKNOWN; a successful sequential fallback remains a completed result. No extra provider request or candidate action occurs. At most 32 cases and 256 KiB are kept. Active capture uses a nonblocking recorder; concurrent busy samples are dropped and counted. No file I/O happens in a login. Unsupported records are counted and cannot change admission.

`stop` validates the complete sample and atomically creates `policy/observations.json`. An earlier archive is never overwritten; replay/clear it first. A failed save keeps the in-memory sample for an explicit retry. `discard` cancels an unsaved sample. Shutdown discards unsaved data, without auto-saving. The recording window expires after at most one hour. Unsaved and saved observations are removed after seven days while the plugin runs; an expired saved file is removed at the next startup if the server was stopped. Status exposes corrupted/unremovable archives for operator review. Saving publishes a fully written private staging file through an exclusive hard link; filesystems without this support reject saving rather than use a non-atomic fallback. POSIX files are owner-readable/writable only; other platforms use their local ACLs.

Replay compares the **current policy and candidate**, not the original policy that happened to be active during capture. `now` (default) applies their rules and source expiry at the present time; `captured` applies each case's original evaluation timestamp and clearly labels it as historical. Both preserve the recorded trust/exemption inputs; neither proves a current identity or refreshes provider data. The output never displays a captured IP/UUID. Local samples are not included in Cloud sync, error reports, webhooks or addon events. Changed outcomes are not false positives or an accuracy estimate.

## Live shadow comparison

After reviewing `policy/candidate.json`, enable a comparison explicitly:

```text
/cg policy shadow start candidate 15m
/cg policy shadow status
/cg policy shadow stop
```

The same `connectionguard.command.policy` permission applies. Supported windows are `5m`, `15m` (default) and `1h`. There is no automatic start, scheduler or upload. Shadow counters remain in memory and contain no saved observations. A candidate is immutable for the session; editing its file does not silently change a running comparison. At most 128 candidate rules, 10,000 reserved samples and eight simultaneous comparisons are allowed. Busy samples are skipped and counted, rather than queued. Work already reserved can finish after stop; `pending` exposes this. One active window is allowed; starting a replacement requires stopping the current window first.

At the final policy evaluator, the candidate consumes the **same** existing VPN/geo facts, evaluation timestamp and resolved identity/exemption inputs as the live policy. It does not query any provider, fetch missing facts, use a candidate cache namespace, send a second observer/Cloud event, execute commands or kick players. Candidate failure ends the comparison and increments a redacted error count; the actual live result is returned unchanged. Counters remain in memory until a new window or restart. Observed IPs, UUIDs, names and provider response bodies are not retained by the shadow session. Configured candidate rules may contain operator-supplied IP/UUID targets just as ordinary access rules do.

Status identifies the two decision policies with SHA-256 fingerprints and reports changed effective refusal reasons, flags and selected rules; unknown VPN/geo facts, busy skips, pending work, errors and candidate evaluation time are separate. The fingerprint covers decision settings and ordered access rules, including expiry. It excludes provider credentials and operational settings; the session separately checks the current configuration generation and immutable rule snapshot. A successful reload, provider/threshold/cache change, manual or Cloud rule change ends the session as `BASE_CHANGED`, retaining the old counters. Even an idle session expires at its monotonic deadline on status read. Invalid drafts rejected before activation leave the old comparison and live settings active.

This is a sample of calls that reach the **final policy evaluator**, not every login. Earlier admission, ban/challenge, literal-rule refusals and skipped lookups may be absent. Missing/exempt facts stay missing; a candidate cannot undo an earlier exemption resolution or reconstruct a lookup that did not run. The window does not freeze remote provider behavior or refresh local data; new completed source facts are used equally by both policies. It does not replay actions already performed elsewhere in the pipeline. Changed outcomes are **not false positives**, a detection rate or a rollout approval. [Separate explicit activation and rollback](POLICY_VERSIONING.md) do not run as part of comparison.

## JSON contracts (schema 1)

The linked files are executable examples. Every listed property is typed. `captured_at`, `cached_at`, `valid_until` and rule `expires_at` are nonnegative epoch milliseconds; `0` means unavailable capture/cache age or permanent/no source expiry respectively. Source versions are lowercase SHA-256 or null. Durations are milliseconds, at most 3,600,000. Synthetic files declare `kind: "synthetic"`. The local writer emits `kind: "saved_observations"` with typed `identity_redacted`, a positive collection `captured_at`, and a positive `observed_at` for each case. Redacted cases require the fixed documentation address and either a null UUID or the fixed zero UUID when trusted. Identity-mode cases retain the normalized actual address/UUID. Synthetic input permits 64 cases; saved observations permit 32. Saved VPN data additionally records the actual `status`, `reason` and `strategy` (`FAILOVER` or `CONSENSUS`); contradictory voting facts are rejected.

VPN aggregate status is derived from the saved voting source facts and captured `threshold` (1–16). Enrichment-only sources do not vote. Source status accepts `POSITIVE`, `NEGATIVE`, `UNKNOWN`; only `UNKNOWN` may have a non-`NONE` failure reason. Geo has either a two-letter uppercase country with reason `NONE`, or null country/ISP/ASN with a non-`NONE` reason. Classification fields, ASN, provider-specific decimal risk and confidence are optional observations; absent values stay unknown.

Rule effects: `DENY`, `ALLOW`, `EXEMPT`; scopes: `VPN`, `GEO`, `ALL`. Targets follow [access-rule syntax and precedence](ACCESS_RULES.md), including literal IP/CIDR, canonical UUID, exact metadata and source-specific risk thresholds. UUID rules require a captured or synthetic trusted identity. A literal DENY wins even when a permission exemption was resolved before that deny was added; metadata rules keep existing permission-exemption behavior.
