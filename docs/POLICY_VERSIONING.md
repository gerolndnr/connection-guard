# Local policy activation and rollback

Development implementation in `0.5.2-SNAPSHOT`; absent from stable `0.5.1`. Native qualification remains required before delivery. Review a complete [candidate](../ci/fixtures/policy/candidate.json), use [synthetic replay and live shadow comparison](POLICY_REPLAY.md), and separately authorize a durable change. Neither replay nor shadow activates anything.

```text
/cg policy inspect candidate
/cg policy status
/cg policy activate candidate <candidate-hash> <expected-token>
/cg policy history
/cg policy rollback <revision> <expected-token>
/cg policy release <expected-token>
```

Use the exact SHA-256 candidate hash from `inspect` and the current `expected` token from `status`, after reviewing the candidate and current conditions. Tokens identify a base, not an authorization credential. Read commands require `connectionguard.command.policy`; writes additionally require `connectionguard.command.policy.activate`. Bukkit defaults both to operators. Proxy permission systems must grant the selected nodes. The candidate path restrictions and strict 256 KiB JSON validation are unchanged. Activation allows at most 512 rules; an empty `rules` list deliberately replaces all current rules with none.

An edit to current rules, provider configuration, threshold, cache source conditions or decision settings invalidates the reviewed token. Editing the candidate's decision content invalidates its reviewed hash; whitespace alone does not. Rejected commands report redacted diagnostics and preserve active state. Recheck the files and conditions before issuing a new token; never automatically retry an uncertain write response.

## Persistence and recovery

`access-rules.json` becomes one private, versioned document containing decision settings, rules, ownership and at most three revisions. Existing legacy rule arrays remain readable; the original bytes are preserved once as `access-rules.before-policy.json` when the first local revision is activated. This backup is not automatically restored. Shared staff and existing dashboard rule additions, removals and expiry pruning append to the same history, so frequent edits can evict a desired older revision. Keep deliberate private backups when longer retention is needed.

Validation, serialization and the next immutable state are prepared first. The writer flushes a private temporary file in the same directory and uses an atomic rename, then publishes settings and rules through one immutable state. A persistence or rename failure leaves the old in-memory state and committed file active. Atomic rename must be supported by the filesystem; there is no non-atomic fallback. This provides a coherent restart from the committed document. Directory metadata is not fsynced: hardware failure and sudden loss of filesystem metadata are outside this guarantee. A damaged committed document rejects startup; an invalid explicit reload preserves the running state rather than silently falling back to an old backup.

At initialization, up to 32 regular files matching the exact `.cg-policy-<digits>.tmp` staging namespace are discarded. Their contents are never interpreted as committed policy. Other names remain untouched; symlinks, nonregular staging files or more than 32 matches require operator review. External edits to the committed file are detected before writes and require an explicit valid reload. The file hash check is not a cross-process filesystem transaction: do not run multiple writers against one data directory or race a noncooperating external editor against a command.

History retains configured rule targets and notes privately, including older deleted rules; it stores no observed players, provider keys or response bodies. Original `expires_at` values are never extended by rollback. An already expired restored rule remains inactive in both scopes and can be pruned normally. A rollback restores a complete selected decision policy. `release` relinquishes local decision-field ownership to current native configuration while retaining the current rules; it does not restore the legacy backup or original rules.

## Ownership, concurrency and existing caches

Status and `/cg doctor` identify `CONFIG` or `LOCAL_VERSION` and the current revision. In `LOCAL_VERSION`, mode, scoped failure policies, kick switches, geo type/list and rules come from the journal. Provider/quorum/transport/budget/admission/identity/webhook choices remain configured. Operational reloads are allowed when native decision fields remain unchanged. Changed native decision fields are rejected until `release`. A dashboard overlay containing any managed decision field prevents local activation; reset those managed fields first. A new dashboard decision-field draft is rejected while local ownership applies. Existing dashboard access-rule commands remain available and invalidate a reviewed base. Dashboard snapshots report the actual locally effective values using existing fields; no new Cloud enums, commands or observed-data types are introduced.

A restart with both a locally owned journal and managed dashboard decision fields rejects initialization; correct or reset the conflicting overlay deliberately before enabling checks. No stale backup is silently selected. Whole-document reload and direct internal transitions use the same pending-login and ownership guard; normal shared rule edits retain their late-DENY behavior. Ordinary `/cg reload` reloads native/provider configuration, not an externally edited rule journal; use a deliberate restart for that file.

A decision lease covers each platform's captured login, including pending permission/admission work before any HTTP request and configurations without observers. Activation, rollback and release require no active leases and no pending provider/cache workers. Native provider drafts and source/quorum/cache/TTL setter changes also wait for leases, so a permission-pending login cannot begin HTTP with a different configured source generation. Early denial, callback errors, completion and shutdown release or invalidate leases. Core-only decision-setting updates retain already captured settings; native reloads take the stricter complete-provider-draft path. Manual or Cloud rule edits remain allowed during a login: one current immutable rule list supplies both final scopes, and a literal DENY added during pending geo retains priority over an earlier VPN exemption.

Caches hold source facts, not final policy outcomes. Changing decision fields or rule revisions can reuse still-valid facts without another lookup or namespace change. Source/quorum changes retain their existing validation, invalidation and expiry behavior and invalidate review tokens. Commands never issue candidate provider requests or candidate actions. Saved live observations and verified rule-specific temporary identity grants remain separate, unfinished work; this implementation alone is not the complete differentiator.
