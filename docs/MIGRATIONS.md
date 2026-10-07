# Migrate from another anti-VPN plugin

Connection Guard detects sibling configuration folders for **FoxGate, ProxyShield,
VPNGuard, KauriVPN and AdvancedAntiVPN**, including their lowercase proxy folders.
It offers a preview once in the startup console. Detection alone changes no
configuration, imports no keys/rules and sends no IPs to a service.

## Apply a reviewed migration

1. Back up the server, stop the old plugin cleanly and remove its JAR from `plugins/`.
   Retain its data folder. Do not run two independent connection guards together.
2. Install Connection Guard and start the server. Use `cg migrate` in the console
   to see detected sources, then `cg migrate preview foxgate` (or another ID below).
3. Read the **complete console preview**, especially mode, failure policies, IP
   recipients, country-source changes and unresolved rules. It prints no keys,
   player names, UUIDs or IP-rule values. Blockers must be resolved first.
4. Run `cg migrate apply <preview-id> reviewed` **from the server console**. It
   stages a private plan; current protection remains active. `cg migrate cancel`
   cancels it before restart. `/cg reload` does **not** apply a migration.
5. Stop cleanly and restart. CG checks source/target fingerprints, validates the
   whole config and rule set, backs up originals, then commits before loading any
   native configuration/cache/rules. `cg doctor` and actual allowed/blocked logins
   should confirm the intended result before reopening the server.

Permission: `connectionguard.command.migrate` (operator by default on Paper).
Sources: `foxgate`, `proxyshield`, `vpnguard`, `kaurivpn`, `advancedantivpn`.
There is one bounded background worker; migration performs no login-time work.
SQLite/H2 drivers may be fetched from Maven Central on demand; player IPs are never
sent for migration. Kauri's local H2 reader requires **Java 11+** and a compatible
database; legacy formats require an explicit local export instead of guessed data.

## What transfers

| Source | Configuration and explicit rules |
| --- | --- |
| FoxGate 1.2.0-pre10 schema | Passive mode; file whitelist IPs/CIDRs/canonical UUIDs; enabled compatible service keys from known official endpoint hosts. Configured PLUS country list when the license field is populated; module details require review. |
| ProxyShield 2.5.1 schema | Dry run, fail-closed, enabled country rules/mode/unknown-country policy, IP/CIDR/range and UUID lists, enabled explicit ASN lists, compatible active provider keys. |
| VPNGuard 1.2.0 schema | Join enforcement/OBSERVE and notifications; compatible active provider keys; SQLite `database.db` explicit IP and canonical UUID whitelist tables. |
| KauriVPN 1.10.x schema | Kick/notify choice, country list and whitelist/blacklist intent; local H2 `databases/database.mv.db` UUIDs and IP/CIDR ranges. `license` is not reused as a provider key. |
| AdvancedAntiVPN 2.31.8 schema | Kick/notify choice, active compatible keys, country intent; SQLite `database.db` IP/UUID/wildcard admin allow/deny tables. GeoLite2's MaxMind key is not an API key. |

IP ranges are decomposed into equivalent CIDRs without widening them. Trailing
IPv4 wildcard octets such as `192.0.2.*` become a `/24`. Arbitrary wildcard patterns
and invalid **deny** selectors block application. Names and name prefixes are
reported for explicit UUID mapping: CG never resolves them over the network or
creates spoofable name bypasses. Imported UUID rules still require authenticated
or explicitly trusted identity; configuring secure forwarding remains your task.

Existing CG rules are retained and equivalent entries deduplicated. **DENY wins
over ALLOW**, which can differ from an old whitelist's precedence. At most 512 rules
are supported. Unknown schemas, active SQLite WAL/journal/SHM files and H2 locks
require review; caches never become permanent access rules.

## Keep Connection Guard's advantages

The migration starts from the **bundled CG defaults**, merged with existing CG
configuration. Explicit existing settings that differ from bundled defaults win
and appear in the report. It retains ordered failover, local Tor/Intel detection,
bounded queues, request coalescing, circuit/quota visibility, configured identity
verification, cache and Cloud controls. Timeout/worker/queue/inflight/circuit
defaults are not replaced with another product's values.

Compatible active ProxyCheck, IPHub, VPNAPI and IPQualityScore keys can be copied
privately. Key-required services with no usable key remain disabled. Additional
keyed sources enter the failover order after ProxyCheck, before the free fallbacks;
an individually configured CG order wins. Existing CG keys are not overwritten.
Source scoring weights, quorums, `maxFlags`, consensus thresholds, broad hosting
blocks, reverse DNS, automatic trust/playtime history and historical verdicts are
**not equivalent** and are not transplanted. CG's concrete evidence and review
semantics remain in effect. This can change decisions; validate actual traffic.
No improved detection rate, false-positive rate or benchmark rank is inferred.

Foreign commands, webhooks, reverse proxy settings and remote database credentials
are not enabled. Manual-only VPNGuard becomes OBSERVE. An explicitly disabled
source kick option does not silently become a block. Unsupported custom punishment
commands need manual replacement; they are never executed during migration.

## IP recipients and country rules

Reviewed application can introduce CG's configured fallback recipients. The preview
lists the enabled services. Include them in your server privacy information before
applying; [provider disclosures](PROVIDERS.md) and [privacy controls](PRIVACY.md)
give the service/operator details. Blackbox includes hosting/cloud lists and has
no written terms; zowi is run by FoxGate's developer. **ip-check.net publishes no
operator, terms or privacy policy** and stays disabled unless explicitly enabled
in the source and acknowledged by reviewed apply. IP-API free uses HTTP and has
non-commercial terms. Any opted-out existing CG setting stays opted out.

Without country rules, Geo stays Disabled. Imported active country rules select
CG's **shared ProxyCheck** lookup, which can replace the old GeoLite/MaxMind or other
geolocation source. That is a disclosed source change, not an equivalent local
GeoLite import. Existing custom CG country/source settings win. UNKNOWN countries
follow the displayed CG failure policy. Cloud and local Intel use existing CG
controls; migration sends no new Cloud data types.

## Non-equivalent modules or remote databases

Conditional FoxGate IP/ISP/ASN modules, unknown database formats and remote
MySQL/Mongo lists are not guessed or silently dropped. They can block migration.
Export **only explicit admin rules**, using the old product's admin/export tools,
and review how its conditional exceptions map to CG's deny precedence. Put this
private, complete local export in the competitor's folder as `migration-rules.yml`:

```yaml
schema: 1
complete: true
allow: ['192.0.2.10', '01234567-89ab-cdef-0123-456789abcdef']
deny: ['198.51.100.0/24']
```

`complete: true` is your explicit declaration that all intended persisted admin
rules and non-equivalent module choices were reviewed. It selects this export
instead of database rules; main-config/file rules can still be deduplicated into
it. It must never contain cached provider results. Unsupported deny patterns still
block it. Country intent remains a separate configuration mapping. Re-preview
after completing the export or editing configuration.

## Backups and recovery

`migration/pending.json` contains private keys and rules. Backups and the redacted
report are in `migration/backups/<preview-id>/`; private files use `0600` on POSIX.
Do not publish pending plans, admin databases or config backups. Original source
files and JARs are never modified or deleted by CG.

An interrupted two-file commit rolls forward on the next startup only when both
active files still equal the recorded old/new hashes. Unexpected external edits
stop recovery rather than overwrite operator changes. Preserve `migration/commit.json`
and backups when resolving a conflict; do not remove half a transaction.
Ordinary discovery/stale-preview failures retain active CG files and protection.

For rollback, stop the server, retain the migrated files separately, restore the
original config/rules from the backup, or remove a newly created file if the report
says its original was absent. Clear a completed migration's pending/commit markers
only after restoring a consistent pair. Restart and verify actual login behavior.
Cloud overlays and versioned local policy journals must be explicitly released or
exported before migration; CG does not rewrite their ownership/history.

Download and docs: https://connectionguard.net/download
