# Local lists and Geo/ASN data

The built-in Connection Guard Intel source below belongs to the unreleased 0.5.2 candidate.
Operator-provided local lists/MMDBs are separate, optional sources.

Operator-provided local sources are optional and disabled by default. They do not send player IPs to a detection API. A configured
public list download contacts its host to fetch the list, without a player IP. No data subscription or automatic
license/account acceptance is performed. Attribution declares your actual data source and license; it does not
grant a license or validate the accuracy of that source.


## Built-in Connection Guard Intel (0.5.2 candidate)

New installations enable `provider.local.connectionguard-intel` with a daily background
update from **https://intel.connectionguard.net/**. Existing configurations remain off
unless they explicitly opt in; a once-only console recommendation never edits YAML.
The HTTPS list fetch sends **no player IP, UUID or name**. The host sees the server's
normal download connection; switching Cloud off does not switch Intel off.

```yaml
provider:
  local:
    connectionguard-intel:
      enabled: true
      update-hours: 24 # 0 disables automatic fetches; manual update remains available
      max-age-hours: 72
      relay: ALLOW # ALLOW or VPN
```

The bundled ECDSA P-256 key authenticates the exact manifest bytes with Java8
`SHA256withECDSA`. The endpoint and trust key cannot be changed by YAML or Cloud;
key rotation requires a plugin update. A bounded manifest (64KiB) and signature
(256 bytes) are verified before any lists are fetched. VPN, TOR, RELAY and HOSTING
files must match the signed size, SHA-256 and record count. List limits remain
4MiB/100,000 networks each. Evaluation-only, future-dated, rollback and malformed
bundles are rejected. Source attribution/terms are published in the signed manifest
and the [public Intel repository](https://github.com/gerolndnr/connection-guard-intel);
its licence does not replace upstream source terms or prove detection accuracy.

All four immutable indexes activate together behind one atomic disk-generation
pointer. Every load rechecks signature and file hashes. An update failure keeps
the last good generation; an in-flight login never sees a partially replaced set.
Publication uses the existing quiescent local-data activation gate and can stage
for `/cg local reload` if admissions remain busy. The startup fetch is background
work and is not awaited by logins. No data subscription or terms are accepted.

Failover checks bundled Tor first, then Intel, then configured APIs. A VPN/TOR hit
is positive and avoids subsequent VPN-provider queries. Pure RELAY membership with
`relay: ALLOW` is a negative generic VPN verdict and stops the sequential chain,
with RELAY metadata retained; it is **not a permission/access-rule exemption**.
Explicit Intel VPN/TOR hits take precedence over overlapping RELAY ranges.
`relay: VPN` makes a relay-only hit positive, still classified as RELAY rather than
inventing a VPN type. HOSTING alone remains UNKNOWN review evidence, allowing the
chain to continue. Unlisted addresses are UNKNOWN, never an assumed clean IP.
Consensus mode retains its normal multi-source aggregation and may consult APIs.
A separately selected external Geo source still performs its configured Geo check.

Data becomes STALE/UNKNOWN after the signed `as_of` plus `max-age-hours`; stale
classifications cannot block or enter metadata rules. Cache facts cannot outlive
that expiry; generation, freshness setting and relay choice partition the cache.
`/cg local status`, `/cg providers`, `/cg doctor` and `/cg explain <IP>` identify
`connectionguard-intel`, observed types and manifest `as_of`. To fetch manually:
`/cg local update connectionguard-intel`, requiring the existing local permission.

The coordinated Cloud settings are the Boolean `.enabled` and enum `.relay` only.
Health/event ID: `connectionguard-intel`. Per-source optional `types` contains only
observed true categories, at most five; `data_as_of` is the manifest epoch ms.
Unknown fields are omitted. Hosting is review, and allowed relay remains a
NEGATIVE source with RELAY metadata. The Cloud schema accepting these optional
fields must be deployed before integration; this adds dataset provenance, no
new personal-data category. Full comparative acceptance of the exact new candidate
remains pending; overlapping source/benchmark lists are not independent evidence.

## Evidence and freshness

- A fresh VPN, PROXY, TOR or RELAY list membership contributes one positive generic VPN vote, with that specific
  classification. No membership means UNKNOWN / `NO_EVIDENCE`, **never a clean negative**. A list is not proof that
  every unlisted address is residential. Multiple sources may be correlated.
- HOSTING membership records `HOSTING=true` for explicit metadata rules. HOSTING, GEO and ASN are enrichment
  sources, excluded from the generic positive threshold and its complete-negative count. A hosting network alone
  does not prove VPN use. A metadata rule can deny an observed hosting type even when the generic VPN status is UNKNOWN.
- Missing data is UNKNOWN; stale data is UNKNOWN / `STALE_DATA` with no usable stale metadata. Normal OPEN/CLOSED/
  OBSERVE failure policy applies. No list type is implicitly given additional permissions or exemptions.
- Imports take an explicit UTC `as-of` timestamp for a list. Download age uses HTTP `Last-Modified` when present;
  otherwise the diagnostic explicitly says `FETCH`, an acquisition timestamp rather than a claimed publication date.
  MMDB age always uses the database's embedded `build_epoch`, **not the import date**. Recopying a database cannot
  make old data fresh. All sources use their configured `max-age-hours`; the setting is required to reflect your use case.
- `/cg local status`, `/cg providers` and `/cg doctor` show active type, readiness, content SHA-256, data age, fetch age,
  time basis, family coverage/database type and source/license/notice. No download endpoint appears in these diagnostics.
- Local content or data-time/configuration changes create a different cache namespace. Cached source facts also carry
  an expiry; they cannot outlive their local data just because the normal SQLite/Redis cache TTL is longer.
  The three login adapters and explain also check at the final decision time, after both scopes have finished.
  Expired source facts become UNKNOWN; still-fresh voting sources are reaggregated using the captured threshold,
  which is not lowered. Expired enrichment cannot erase an independent fresh positive.

## Configuration

Use your own licensed list first. This example uses synthetic attribution placeholders, not a supplied production list:

```yaml
required-positive-flags: 1
provider:
  vpn:
    proxycheck: {enabled: false}
    ip-api: {enabled: false}
    iphub: {enabled: false}
    vpnapi: {enabled: false}
    custom: {enabled: false}
    local: {enabled: true}
  local:
    update-hours: 0
    sources:
      - id: vpn-list
        type: VPN
        source: 'Your actual public source or operator-provided dataset'
        license: 'Your actual license'
        notice: 'Actual copyright/attribution notice'
        max-age-hours: 168
      - id: hosting-list
        type: HOSTING
        source: 'Your actual source'
        license: 'Your actual license'
        notice: 'Actual attribution'
        max-age-hours: 168
  geo:
    service: Disabled
```

Disable every other enabled custom/native API source when choosing local-only operation. Geo can be `Disabled`
(UNKNOWN) or `Local`; IP-API/ProxyCheck still query their APIs if selected. Keep a threshold that your actual voting
sources can satisfy. Local absence remains UNKNOWN even with an API-free configuration; review the failure policy
and initially use OBSERVE. New settings do not replace an existing operator's ENFORCE choice.

List formats are UTF-8, one literal IP or CIDR per line, with blank lines and full-line `#` comments. No hostnames,
DNS lookups, CSV/HTML/JSON guessing, inline comments, compressed archives or scoped IPv6. IPv4-mapped IPv6 is
canonicalized to IPv4. Native families remain separate; an IPv4-only list does not imply IPv6 coverage.

An optional list source can have a `download-url`: public HTTPS, no credentials/query/fragment and no redirects.
Do not put tokens in source/notice fields. The fetch is bounded to 10 seconds and 4 MiB of decompressed body.
`update-hours: 0` disables automatic downloads; 1..168 opts in. The first scheduled download occurs after that interval.
All updates use one worker with a queue of four, separately from login lookups. Sources without a download URL
and MMDB sources are not automatically fetched. Failures keep the previous source manifest. If lookup admission
prevents publication, validated files remain staged; background publication retries once after one minute, and
the log tells the operator to use `/cg local reload` if still busy. New data is only used after complete activation.

## Import and activation

All local commands require `connectionguard.command.local` on every platform; no file/network work starts without it.

1. Configure attributed sources, then `/cg reload` to validate settings. Missing sources start as UNKNOWN.
2. Run `/cg local prepare`. Copy your files into `local-data/inbox/` **inside the plugin data directory**.
3. Run `/cg local import vpn-list vpn.txt 2026-10-02T09:00:00Z`, using the actual list as-of date.
4. Inspect `/cg local status` and `/cg explain <literal-IP>`. Explain may use any API sources that remain enabled.
5. For an explicitly configured public list URL, `/cg local update vpn-list` fetches, validates, stores and activates it.

Imports accept only a plain inbox filename, not an arbitrary path. Symlinked files or parent directories are refused.
Files and manifests are private (0600; local directories 0700 on POSIX, platform ACLs on Windows). A complete
content-addressed file is stored before an atomic manifest swap; failed parsing/hash/type/size checks do not replace
the manifest. Readers hold immutable heap snapshots, so a file replacement cannot partially change an active reader.
Successful publication requires quiescent lookups and a complete configuration draft. A command explicitly reports
when valid files were staged but could not be activated; use `/cg local reload` after active lookups finish.
Changing a source's attribution/type under an existing ID requires a newly attributed import; use a new source ID
when introducing a differently licensed dataset. Changing freshness limits does not renew the dataset's timestamp.

Only the current owned content generation is retained on disk after a successful source update. Operator-provided
inbox files are left for the operator to manage. Unsupported atomic moves reject the operation rather than writing
an in-place partial manifest.

## Operator-provided MMDB

Add at most one GEO and one ASN source to `provider.local.sources`, with their actual attribution and license:

```yaml
- id: country-db
  type: GEO
  source: 'Your actual country/city data publisher'
  license: 'Actual database license'
  notice: 'Required database attribution'
  max-age-hours: 720
- id: asn-db
  type: ASN
  source: 'Your actual ASN data publisher'
  license: 'Actual database license'
  notice: 'Required database attribution'
  max-age-hours: 720
```

Choose `provider.geo.service: Local` for country checks. Enable `provider.vpn.local.enabled` to expose local GEO/ASN
as source-specific metadata to VPN-scope rules too. Import country/city and ASN files using the same import command;
the supplied timestamp is ignored for freshness in favor of `MMDB_BUILD`. Data acquisition and the publisher's
license/EULA remain operator-controlled. The MIT license on our synthetic test fixtures does not cover GeoLite/GeoIP
production databases. [MaxMind's GeoLite documentation](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data/)
describes their own account and licensing requirements.

Connection Guard's focused Java-8-compatible MMDB reader supports binary format v2, IPv4/IPv6, 24/28/32-bit tree
records, maps/arrays/pointers and normal primitive values. GEO database types must end in `-Country` or `-City`, ASN
types in `-ASN` (each may have `-Lite`). GEO uses actual `country.iso_code`, optional `city.names.en`; ASN uses actual
`autonomous_system_number`/`autonomous_system_organization`. Missing country is UNKNOWN, not registered-country
fallback. Missing ASN is not zero. The local geo result can include fresh ASN information without another transport.

Limits: eight configured sources, at most one GEO + one ASN, 64 MiB per MMDB (128 MiB resident files for a complete
pair, plus transient draft/import copies), 4 MiB / 100,000 networks / 200,000 physical lines / 256 characters per list
line. MMDB metadata is limited to the format's 128 KiB; each decode operation is additionally capped at depth 32,
4,096 decoded values and 256 KiB copied payload. Container limits are checked before allocation; pointer targets,
tree bounds and separator are validated. Import checks structure/metadata; individual lookup records are decoded
with these bounds and invalid records yield UNKNOWN. It does not exhaustively expand the whole data section.
These deliberately strict limits can reject a valid database with unusual records; such data is not treated as clean.

The reader follows the [MMDB v2 specification and resource-limit guidance](https://maxmind.github.io/MaxMind-DB/).
We use a focused reader to retain the plugin's Java 8 core and apply these limits, without bundling an older general
decoder. Heap snapshots avoid mapped-file replacement locks on Windows. Verified synthetic country/ASN round trips,
hostile pointer/payload rejection and provenance hashes are in `core/src/test/resources/mmdb/` and local tests.
They prove behavior on those fixtures, **not detection accuracy or every production database's compatibility**.
