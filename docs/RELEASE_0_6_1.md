# Connection Guard 0.6.1

## Changes

- Reduce broad Blackbox blocks: a positive Blackbox answer requires fresh local Intel HOSTING evidence by default; otherwise the normal failover chain continues. Optional signed Intel PROXY data can block locally, without sending player IPs to Intel.
- New installations use local Tor/Intel → zowi → confirmed Blackbox → IPQuery. ProxyCheck defaults off and remains an optional late fallback; ip-check.net and IP-API default off. Existing provider choices, order, keys and ENFORCE/OBSERVE mode are retained. zowi remains capped locally at60/minute; quotas/timeouts fall through to the remaining selected services.
- Manage partial/empty provider order, sequential/parallel strategy, 1–16 external attempts and IPQuery/IPQualityScore through the existing dashboard after the plugin snapshot. Changes apply without restart, preserve config.yml and reject an invalid update in full. Local sources always precede APIs and IP-API stays last.
- Add reviewed migrations from FoxGate, ProxyShield, VPNGuard, KauriVPN and AdvancedAntiVPN. Preview and explicitly stage compatible settings/admin rules for restart; retain CG safeguards and skip competitor verdict caches and foreign commands.
- Use a bounded Memory tier before SQLite/coherent Redis, batched SQLite writes and asynchronous optional Velocity cache initialization. Keep fact age, namespace and invalidation fences. Redis without tracking retains remote reads; clean shutdown flushes accepted writes.
- Custom TEXT webhooks can choose player name, UUID, VPN status, IP, country/city/location/ISP and UTC time. Privacy controls and unknown values remain; no extra Geo lookup is made.
- Add explicit local policy capture/replay, off by default and bounded/private, with no Cloud sync of recorded samples. Add LOCAL_ONLY and shared daily API-budget controls plus read-only profile overlays and visible quota diagnostics.

## Upgrade and privacy

Back up config.yml and data, replace the main JAR, and restart. Existing selections are retained; the new recommended provider order is not silently migrated. Review `/cg doctor`, `/cg providers` and `/cg cloud settings`. New installations use ENFORCE: recognized VPN/proxy/Tor evidence can be denied immediately; select OBSERVE explicitly for evaluation.

The enabled API services receive the queried player IP. zowi is operated by FoxGate's developer; Blackbox has no written terms; ip-check.net publishes no operator, terms or privacy policy and remains opt-in. IP-API's free service is non-commercial, HTTP-only and opt-in. Consult [provider recipients and controls](https://github.com/gerolndnr/connection-guard/blob/0.6.1/docs/PROVIDERS.md).

Cloud remains optional and on by default: `cloud.enabled: false`, `/cg cloud disable` or `CONNECTIONGUARD_CLOUD=false` turns it off. Bounded error reports can be disabled separately with `cloud.error-reports: false`. [Plugin privacy and data controls](https://connectionguard.net/privacy#plugin).

## Verification and limits

The full independent competitive benchmark of this exact combined artifact is still pending. No improved detection rate, false-positive rate, latency or market-leadership claim is made. Quota exhaustion can change which sources decide; source replay and older benchmarks do not qualify this new artifact. Exact release-CI, reproducible archives and the named runtime checks are recorded with the release; start/help/reload/stop checks do not imply full qualification of every listed platform/version or real-account flow.

The main plugin is0.6.1. Optional LibertyBans/Limbo addons retain their own0.1.0-dev versions and are separate downloads.

Download and docs: https://connectionguard.net/download
