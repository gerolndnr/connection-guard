# Lookup profiles and a shared daily API cap

**Requires 0.6.1 or newer.** Version 0.6.0 does not implement
these new lookup fields or the profile command. Its existing individual provider
switches remain documented in [Local data](LOCAL_DATA.md).

Profiles are explicit operator choices. Existing configurations retain their mode,
enabled sources, keys, failover/consensus choice, thresholds and timeouts. Missing
new fields mean `lookup.api-policy: CONFIGURED` and `lookup.api-daily-budget: 0`.
The new-install template uses these same neutral values.

`/cg profile list` and `/cg profile show <local-only|budgeted-api|observe> [daily-limit]`
show ready-made overlays; they never apply a change, query a provider or write a file.
Merge the selected fields into the existing `config.yml`, then use `/cg reload`.
The complete draft is validated; rejection leaves the old runtime intact.
The command requires `connectionguard.command.profile` (Spigot: operators by default).

## Local hints only

```yaml
lookup:
  api-policy: LOCAL_ONLY
```

The guard skips API, custom HTTP and versioned extension VPN calls, and skips API
Geo calls, even if those sources remain enabled in the config. Existing Tor,
fresh selected Intel/local lists, local MMDB and local cache remain available.
It does not enable/download a new dataset or infer a negative from absence.
Suppressed sources are UNKNOWN/NO_PROVIDER. An explicit consensus threshold
is preserved; one local vote cannot silently satisfy a two-vote requirement.
Existing ENFORCE/OBSERVE and failure-policy still decide what UNKNOWN means.
Choose OBSERVE explicitly when evaluating uncertain local data.

Local-only isolates the evidence cache namespace from API-derived cache entries.
Returning to CONFIGURED does not replenish the consumed daily budget. Profile
changes can produce cold misses; keeping CONFIGURED or changing its cap keeps
the existing namespace and valid warm facts.

This controls **VPN/Geo source lookups**, not all networking in the process.
Cloud, webhooks, remote cache, list updates, admission/identity integrations and
other installed plugins have their own controls. Core-injected providers declaring
`isLocal()` are trusted local implementations; the versioned extension adapter
does not make that declaration and is suppressed.

## API with a daily budget

```yaml
lookup:
  api-policy: CONFIGURED
  api-daily-budget: 1000
```

1000 is this consciously selected example's cap, not a changed default. Already
enabled API sources become eligible; no source, key or country rule is added.
Local-first failover or explicit parallel consensus retains its configured behavior.
Existing per-source/day/minute/circuit limits still apply alongside the shared cap.

The counter reserves external source calls across both VPN and Geo, atomically with
the selected source's own quota. A source quota/circuit refusal does not consume
another shared reservation. Shared ProxyCheck VPN+Geo consumes one reservation
for the physical shared request. Cache/local facts consume none. A missing cache
fact after exhaustion remains UNKNOWN/BUDGET_EXHAUSTED and follows the configured
failure policy; fresh cached/local facts can still protect the server.

These are conservative **process-local reservations**, not confirmed HTTP counts or
the provider account's remaining quota. Queued/cancelled/timed-out work can consume
a reservation without completing an HTTP request. A third-party extension can do
its own networking inside/outside its callback; CG limits its callback invocation,
not the network stack of another plugin. Counters reset at UTC midnight or a full
plugin restart, not reload, clear, limit changes or profile switches. Clock rollback
cannot replenish the shared cap. Raising the cap explicitly permits more calls;
zero disables the shared cap while retaining the day's counter and source limits.

`/cg doctor` and `/cg providers` show policy, UTC day, reserved/denied counts and cap.
Console exhaustion warnings are rate-limited; existing unchecked-admission summaries
and counters remain applicable. Cloud protocol and dashboard-writable fields are
unchanged in this package; the new total cap is currently a local operator setting.

## Observe existing evidence

```yaml
operation:
  mode: OBSERVE
```

This overlay changes only enforcement mode. It preserves API policy, budgets and
source choices, including an existing LOCAL_ONLY selection. Guard kicks/flag
commands are suppressed; observation is not proof that a source is accurate.

Bundled overlays are `profiles/local-only.yml`, `profiles/budgeted-api.yml` and
`profiles/observe.yml`. No preset is applied automatically. [Local data](LOCAL_DATA.md),
[provider terms/recipients](PROVIDERS.md), [operation limits](OPERATIONS.md).
