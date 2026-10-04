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

No live observation capture is enabled. Optional consented observation capture, full shadow analysis, versioned atomic activation/rollback and diagnostics for changed provider conditions remain separate implementation work; this preview must not be presented as the full simulation workflow.

## JSON contracts (schema 1)

The linked files are executable examples. Every listed property is typed. `captured_at`, `cached_at`, `valid_until` and rule `expires_at` are nonnegative epoch milliseconds; `0` means unavailable capture/cache age or permanent/no source expiry respectively. Source versions are lowercase SHA-256 or null. Durations are milliseconds, at most 3,600,000. A dataset must declare `kind: "synthetic"`; live observations are rejected.

VPN aggregate status is derived from the saved voting source facts and captured `threshold` (1–16). Enrichment-only sources do not vote. Source status accepts `POSITIVE`, `NEGATIVE`, `UNKNOWN`; only `UNKNOWN` may have a non-`NONE` failure reason. Geo has either a two-letter uppercase country with reason `NONE`, or null country/ISP/ASN with a non-`NONE` reason. Classification fields, ASN, provider-specific decimal risk and confidence are optional observations; absent values stay unknown.

Rule effects: `DENY`, `ALLOW`, `EXEMPT`; scopes: `VPN`, `GEO`, `ALL`. Targets follow [access-rule syntax and precedence](ACCESS_RULES.md), including literal IP/CIDR, canonical UUID, exact metadata and source-specific risk thresholds. UUID requires a synthetic trusted identity. A literal DENY wins even when a permission exemption was resolved before that deny was added; metadata rules keep existing permission-exemption behavior.
