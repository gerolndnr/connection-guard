# Optional LibertyBans admission addon (development)

This addon uses the actual public LibertyBans **1.1.4** API. It is a separate **AGPL-3.0-or-later** work; [license](LICENSE). The main Connection Guard core remains MIT. Source is supplied in this directory. Neither the addon nor LibertyBans/Omnibus classes enter the combined plugin. Reflection resolves public API interfaces through the installed native plugin loader; it does not change the licensing arrangement. No private fields, SQL, NMS, player-name lookup or native writes are used by the addon.

Build from the repository root with `./gradlew check shadowJar :adapters:libertybans:jar`, then run `python3 ci/verify_artifact.py` and `python3 ci/verify_libertybans_addon.py`. The optional JAR is in `adapters/libertybans/build/libs/`. Java 17 or newer is required in addition to your server's requirements. Selected actual [Paper/Folia](../../ci/fixtures/native-libertybans/README.md) and [Velocity/Bungee](../../ci/fixtures/native-libertybans-proxies/README.md) qualification includes installed and absent native SDKs; other versions and authenticated accounts remain unqualified. Development artifacts are not a released stable feature version.

Install the matching developmental Connection Guard JAR, native LibertyBans 1.1.4, and the separate addon in the same platform's plugin directory. Other LibertyBans versions, forks, missing SDKs or unavailable native startup yield UNKNOWN. Restart to install/change native plugins; native/plugin hot swapping is unsupported. After all addons start, explicitly select and reload Connection Guard:

```yaml
integrations:
  admission:
    enabled: true
    ids: [libertybans]
    failure-policy: CLOSED
```

The default is disabled. `OPEN` continues ordinary guard checks when the native read is unknown; `CLOSED` temporarily refuses. `OBSERVE` records facts and suppresses only Connection Guard's own refusal/actions. Native LibertyBans still enforces its bans and may replace the final platform denial message. Connection Guard observations describe its own phase, not final admission by other plugins. On selected Velocity, native LibertyBans can refuse before the guard login phase, producing no guard observation; selected Bungee can instead record the guard hook result before native final refusal. Manual DENY stays ahead of hooks; ALLOW/exemptions cannot skip selected ban checks.

Only active native BAN records in `scopesApplicableToCurrentServer()` are considered, including the native global scope. Mutes, expired/revoked records and unrelated server scopes are not active bans here. Exact current IP victims are always queried. A currently verified UUID additionally selects exact UUID victims, then native default address-strictness applicability if no direct ban exists. This initial exact lookup matters: native applicability may omit first-seen UUID/IP pairs without native history. No UUID/name/history authority is fabricated for unverified/offline sessions; those get exact IP checks only. Verified gateway provenance remains gateway delegation, not independent Mojang/Xbox authentication.

At most two sequential native selections are made per callback, each active-only and limited to one record; current native scope count is bounded to 1024. The core limits outstanding callback chains to 32 and retains slots after caller timeouts/unregister/replacement until those futures complete. Trusted callbacks must return a future representing completion of their native work; arbitrary third-party internals are not sandboxed. Native library/other plugin worker pools are independent. All checks share the existing whole-login deadline; no unbounded wait or additional addon pool. Selected handle/provider removal, changed scopes/strictness, errors, invalid/expired results remain typed UNKNOWN.

CLEAR means only that the selected native check found no ban. It never grants an exemption or bypasses VPN/Geo/rules, completes a challenge, authenticates an account, or creates/removes punishments. No ban reason, victim/name, connection object, database detail or exception text is emitted in observations. The optional generic hook API is documented in [ADMISSION_API.md](../../docs/ADMISSION_API.md).
