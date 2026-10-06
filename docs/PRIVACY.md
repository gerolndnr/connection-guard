# Plugin data and error reports

The [published privacy disclosure](https://connectionguard.net/privacy#plugin) describes Connection Guard Cloud and its processing. Cloud is optional. `cloud.enabled: false`, `/cg cloud disable`, `CONNECTIONGUARD_CLOUD=false` or `-Dconnectionguard.cloud=false` disable Cloud and error reporting. Linked decision events containing connection IPs and trusted UUIDs are separate from the anonymous error metadata described here; see [Cloud controls](CLOUD.md).

## Local setup-notice preferences

The 0.6.0 new-install template selects additional player-IP recipients:
Blackbox, zowi and IPQuery. ip-check.net is available but **disabled by default**; enabling it explicitly adds another recipient. Existing configurations are not changed.
Blackbox lists hosting/cloud as well as VPN/proxy/Tor; its published privacy policy
names Cameron Munroe, while no written terms are published. ip-check.net publishes
no operator, terms or privacy policy. zowi is operated by the FoxGate developer.
Operators must include these recipients in their server privacy information
before opting in; [endpoints, source links and controls](PROVIDERS.md#new-keyless-recipients-in-060).
The `keyless-providers-v052.notice` marker stores only that the upgrade
recommendation has been consumed, not player information.

To show the dashboard join hint only once per authorized staff member and installation, the plugin stores SHA-256 hashes derived from staff UUIDs in `cloud/staff-dashboard-notices-v1.json`. The bounded private file contains neither plaintext UUIDs nor link codes. It stays on the server and is never included in Cloud sync or error reports. Deleting it while the plugin is stopped resets suppression. No player-name/IP field, Cloud event or external request is added by the join hint; see [linking notices](CLOUD.md).

## Anonymous exception metadata, from 0.6.0

[Published error-report disclosure](https://connectionguard.net/privacy#error-reports).

When Cloud is enabled, `cloud.error-reports` defaults to `true`, including existing configuration files that omit the option. Own exception metadata goes to the configured Cloud endpoint with the next regular background sync. The hosted Cloud forwards it to PostHog Error Tracking and shows it in the linked operator's dashboard. Set `cloud.error-reports: false` and reload to disable only error reports. Any Cloud off switch also disables them. A separate one-time console notice discloses the setting to existing installations; `/cg doctor` and `/cg cloud status` show its state, buffer and overflow count.

Reports contain only the exception class, deepest cause class or null, at most 12 Connection Guard code frames (class, method, line or null), a fixed context, a fingerprint, occurrence count and first/last epoch-millisecond timestamps. Foreign-only exceptions are ignored. Exception messages, filenames, paths, URLs, API keys, player IPs/names/UUIDs, console lines, suppressed exceptions and foreign frames are never read into reports. The fingerprint is the first eight SHA-256 bytes as 16 lowercase hex characters, over the exception type and first five own frames. Its canonical input is `type + "\n"`, followed by `class + "#" + method + ":" + line + "\n"` for each frame; a missing line uses an empty string. Messages and context do not enter the fingerprint.

Admission threads only offer references to a bounded nonblocking queue. A separate daemon filters stacks and merges fingerprints in memory. At most 50 entries are retained across incoming exceptions and sanitized reports; overflow is counted and dropped. At most ten fingerprints enter one sync. Throwable references exist briefly in process memory while awaiting filtering; they are never serialized. Neither original exceptions nor reports are persisted to disk. A sanitized pending sync is retained in memory for the existing retry mechanism.

Disabling reports clears captured data and removes `errors` from an unsent retry. An HTTP request already in flight cannot be recalled. If an older Cloud returns HTTP 400 to a sync containing `errors`, the same sequence, events and counters are retried without that field. Error reporting then stays off until the plugin restarts; the existing capability fallback remains available.

Reporting is best effort. Fatal startup errors before configuration/Cloud initialization, a process crash, or stopping before the next successful sync can prevent delivery. Reporting adds no startup/shutdown HTTP request and never waits on a login. A self-hosted endpoint receives the same metadata; use an endpoint you trust. The open Cloud schema is `ErrorReport` in `gerolndnr/connection-guard-cloud/packages/protocol`; the Java contract fixture is `core/src/test/resources/cloud-protocol/sync-request-errors.json`.

[bStats](BSTATS.md) uses separate statistics settings. [Detection providers](PROVIDERS.md) receive queried IP addresses according to the selected provider configuration; the error-reporting switch does not configure either system.


## Connection Guard Intel — 0.6.0

Fresh installations select the built-in signed local Intel bundle before VPN APIs;
existing installations must opt in. Daily background HTTPS downloads contact
https://intel.connectionguard.net/ without transmitting player IPs, UUIDs or names.
All four lists activate together after ECDSA P-256 signature, size and SHA-256
verification. Missing/unlisted/stale data is UNKNOWN (default72h); VPN/TOR blocks,
HOSTING only enriches, and RELAY defaults to ALLOW with a separate VPN option.
Endpoint/key are bundled and cannot be managed remotely. See
[local setup, precedence, attribution and test boundaries](LOCAL_DATA.md#built-in-connection-guard-intel-060).
Cloud source `connectionguard-intel` uses the coordinated optional source fields
`types` and `data_as_of`; unknown values are omitted. These features are included in 0.6.0.
The full competitive benchmark of the published artifact remains pending.
