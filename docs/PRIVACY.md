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

## Optional local policy observations (0.6.1)

Policy observation capture is disabled until an operator explicitly runs `/cg policy capture start`. Default `facts` mode replaces real addresses/UUIDs with documentation placeholders while keeping resolved trust/exemption booleans and provider-derived verdict/ISP/operator/country/classification facts. These are redacted local observations, not a guarantee of anonymity: timings and metadata may correlate with other server logs. IP/UUID rules cannot be simulated in this mode.

The additional explicit `/cg policy capture start 15m identities` choice retains the normalized actual connection IP and available UUID for address/identity-rule simulation. The command discloses this choice and status identifies the mode. Neither mode retains player names, cities, request URLs, raw provider responses or credentials. Capture adds no recipient or external request. No sample, capture command failure or replay input is sent to Cloud, error reports, webhooks or addon observers.

At most 32 cases/256 KiB are recorded during a window of at most one hour. The sample is saved only by the explicit stop command using a validated atomic private file `policy/observations.json`. POSIX files use owner-only read/write permissions; other platforms inherit local ACLs. Unsaved samples are discarded on shutdown. Seven-day cleanup runs locally while the plugin is active and on the next startup after downtime; failed cleanup is retried and status exposes invalid/unremovable files. Operators can discard/clear data earlier and must account for optional identity retention in their own server data notice. [Workflow and replay limits](POLICY_REPLAY.md).

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

## Network signals, from 0.6.2

[Published disclosure](https://connectionguard.net/privacy#network-signals).

When Cloud is enabled, `cloud.contribute-signals: auto` (also used when the setting is absent) enables signals for new links. Links that already existed on upgrade remain off; the operator can explicitly opt in with `true`. `false` always disables signals. The local link-default preference survives reloads and restarts; an unreadable preference stays off. Unlinked servers send no signals and keep sending only the anonymous totals described above; logins before linking are not counted later. Every six hours, with a regular sync, the plugin sends, per IPv4 /24 or IPv6 /48 prefix seen in the last six hours, the number of logins, distinct accounts, VPN-positive checks, refusals, logins of accounts with an active ban reported by a selected admission addon, and logins allowed by an operator's access rule. At most 512 prefixes per six-hour window; further prefixes only increase an overflow count. Private, loopback, CGNAT and link-local addresses are skipped.

Full addresses, UUIDs, names, rule IDs and reasons are never sent. Distinct accounts are counted from UUIDs held in memory for the current window only; they are dropped when the window is sent and never written to disk. A failed sync puts the counts back for the retry. The hosted Cloud keeps raw signals 7 days and publishes only prefixes reported by several networks, as a review-only Intel list. Set `cloud.contribute-signals: false` and reload to stop them; any Cloud off switch also stops them. Unlinking stops them and drops the open window. A one-time console notice discloses the setting to existing installations once they are linked.
