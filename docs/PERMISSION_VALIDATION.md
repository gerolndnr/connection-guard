# Permission exemptions: evidence and remaining limits

Status: 3 October 2026. The published release is
[0.4.11](https://github.com/gerolndnr/connection-guard/releases/tag/0.4.11).
The native permission/identity cases below qualify the **development branch for a
future feature release**. Installing 0.4.11 does not install those changes.

## Diagnose an exemption

1. Follow the [permission configuration checklist](TROUBLESHOOTING.md#permission-exemptions).
   Enable the VPN and geo switches separately; a VPN grant cannot exempt a country rule.
2. Confirm which server/proxy holds Connection Guard and LuckPerms. Inspect the
   permission for the actual UUID there, with the context available at login.
   A backend world's context may not exist during a proxy or pre-login check.
3. Determine where that connection's UUID comes from. An offline name-derived UUID,
   an arbitrary forwarded UUID or a Bedrock-style UUID is not authentication.
   For linked accounts, distinguish the canonical linked UUID from an unlinked alias.
4. On the development build, `/cg doctor` reports configured identity declarations
   and native API availability; it does not authenticate a player or verify network
   forwarding. Missing UUID, missing/failing LuckPerms or an expired permission wait
   supplies no permission grant. The configured unavailable-result policy still applies.
5. For optional native Floodgate on a development build, read the
   [identity contract](NATIVE_IDENTITY.md). Its option defaults off. It requires a
   current canonical record bound to the same client socket, checked again after
   waiting. Protect the gateway key and backend access before choosing this trust.

Do not enable `identity.trust-forwarded-uuid` just to make a failing permission work.
It declares operator trust; it cannot verify forwarding or authenticate an offline
client. The declared-trust fixtures below are isolated tests, not deployment advice.
Do not install fixture addons on a production server.

## Named development qualification

All rows use Java 21, actual LuckPerms **5.5.85** and, when installed, actual
Floodgate **2.2.5 build 141**. Provider facts and client accounts are synthetic;
these are controlled boundary tests, not VPN detection accuracy measurements.
Each linked receipt pins the guard, runtime, addon and native plugin hashes.

| Runtime / evidence | Observed cases | Boundary |
| --- | --- | --- |
| [Velocity 3.4.0 build 566 permission fixture](../ci/fixtures/native-permissions/README.md) | Matching server context grants; wrong context, null UUID and missing SDK do not. Ordinary offline login stays untrusted until the isolated test declares forwarding trust. | No backend or authenticated account in these permission fixtures. Global online mode with a per-connection offline override remains untrusted. |
| [Paper 1.21.11 build 132 / Folia 1.21.11 build 14 permission fixtures](../ci/fixtures/native-backend-permissions/README.md) | Matching declared-trust context joins the backend; wrong context is refused. Manual DENY takes precedence. Missing SDK gives no grant. | Real offline backend joins; declaration does not verify forwarding or authenticate the account. |
| [Native encrypted gateway fixtures on those three runtimes](../ci/fixtures/native-identity/README.md) | Actual Floodgate handshakes create canonical socket-bound records; matching native LuckPerms context grants. Disabled native option, wrong context and absent SDK cannot supply that native exemption. Paper/Folia record actual backend joins and manual DENY precedence. | Synthetic gateway assertions, including a supplied linked mapping; no Xbox/Mojang authentication or native account-link registration. |
| [Velocity native lifecycle cases](../ci/fixtures/native-identity/velocity-2026-10-03.json) | Record replacement and controlled native retirement during a provider wait invalidate proof; ENFORCE temporarily denies and OBSERVE reports `IDENTITY_UNAVAILABLE`. A tampered encrypted handshake cannot reach the guard's login authority. | Native retirement is a separately controlled API case; it is not a claim about every ordinary disconnect or Geyser version. |
| [Selected BungeeCord build 2100 / Java 21](../ci/fixtures/native-bungee/README.md) | Actual LP matching/wrong contexts, canonical encrypted native gateway records, alias/wrong-port and tamper rejection, disabled native option, manual DENY and retirement during ENFORCE/OBSERVE waits. Globally online/per-connection offline and absent-SDK variants pass. | Synthetic gateway delegation; no backend, genuine account, real native account linking or verified forwarding. Other Bungee/Waterfall versions remain unqualified. |

PRs [#52](https://github.com/gerolndnr/connection-guard/pull/52),
[#53](https://github.com/gerolndnr/connection-guard/pull/53) and
[#54](https://github.com/gerolndnr/connection-guard/pull/54) contain the implementation
and public reproduction inputs. PR #54's exact development JAR passes 228 tests
with real Redis, nine release/packaging guards, repeatable builds and
[successful CI](https://github.com/gerolndnr/connection-guard/actions/runs/37120305485).
Test count and gateway checks do not establish a complete compatibility matrix.

The newer [platform connection-binding regression package](../ci/fixtures/authenticated-identity/README.md)
re-reads the proxy connection's canonical UUID/name/full socket, authentication
mode and live state before verified authority is used after waits. Its 238 core
tests include synthetic metadata changes; actual selected native gateway
regressions on Velocity/Paper/Folia and a globally online Velocity proxy with a
per-connection offline override pass on the same newer artifact. Positive genuine
account logins, live native authenticated-object mutation and Bungee runtime are
not established by those cases. The newer selected Bungee receipts linked in the
table add gateway and negative authentication runtime boundaries on that same
newer artifact. The earlier receipts retain their original hash and test counts.

## What remains open

[Issue #39](https://github.com/gerolndnr/connection-guard/issues/39) follows a reported
0.4.9 exemption failure. The reporter's stack and configuration are unknown; these
named cases do not reproduce that exact deployment or prove that report resolved.
True Java/backend authentication, verified proxy forwarding, real native linked
accounts, legacy Spigot and other Bungee/Waterfall versions, the separate
geo-exemption runtime matrix and other server versions still require qualification.
Conditional temporary grants and native challenge completion are separate unfinished features.

For an unresolved case, send the [sanitized issue details](TROUBLESHOOTING.md#report-a-reproducible-issue),
including VPN versus geo, identity source, where LuckPerms runs and the context tested.
Remove keys, webhook URLs, player UUIDs/IPs and private logs from public reports.
