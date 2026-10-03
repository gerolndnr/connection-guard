# Selected native modern-forwarding qualification — 3 October 2026

Development evidence for the next feature release; published 0.4.11 is unchanged.
The optional adapter is disabled by default and qualified only for Paper 1.21.11
build 132 / commit c5eb079 and Folia 1.21.11 build 14 / commit 529aabc. This fixture
is synthetic **gateway delegation**, not genuine Mojang/Xbox account authentication.

`CGNativeForwardingFixture.java` runs on an isolated owned backend with the actual
installed LuckPerms API. It registers only synthetic detection and observation;
it does not insert native login records, bypass the native MAC gate or package SDK
classes. `client.cjs` opens loopback-only offline Minecraft 1.21.11 connections.
For raw gateway cases it responds to the actual `velocity:player_info` request with
a version-1 payload signed by a freshly generated private fixture key. Corrupt and
missing payloads exercise the actual native Paper/Folia rejection path. In the
proxy cases, actual Velocity 3.4.0 build 566 emits its own modern forwarding payload
and connects to the single owned backend. No guard addon is installed on that
proxy, so backend decisions and actual backend joins are observed separately.

Exact tested development JAR SHA-256:
`dac5ace1612f8fe027501f8e12fc669d245305af3668db872b216f664c0c206b`.
Named receipts retain checksums of private complete logs and public fixture source
hashes; the complete logs and keys are not published:

- [Paper modern forwarding](paper-2026-10-03.json) and
  [Folia modern forwarding](folia-2026-10-03.json): actual proxy-to-backend joins;
  matching and wrong native LP server contexts; forwarded documentation IP versus
  raw loopback IP; wrong UUID/name capture; valid, corrupt and missing native MAC;
  manual DENY precedence; controlled event-profile canonical-name changes while
  a provider waits; ENFORCE refusal and OBSERVE without lookup facts; disabled
  adapter with an otherwise valid native forwarding payload.
- [Ordinary offline Paper](paper-offline-2026-10-03.json) and
  [ordinary offline Folia](folia-offline-2026-10-03.json): native adapter enabled,
  actual forwarding disabled, public native profile absent at this pre-login
  phase; a synthetic user with real LP exemption still receives ordinary VPN
  denial and `UNTRUSTED` provenance. A global/offline configuration cannot invent
  native gateway proof.

The controlled mutation uses public `AsyncPlayerPreLoginEvent.setPlayerProfile`
to change the event's canonical name while keeping the same UUID. The native
connection remains live and its native gateway profile retains the old name.
The guard's captured proof becomes noncurrent. OBSERVE permits the actual renamed
backend join under the same UUID, while recording `IDENTITY_UNAVAILABLE` without
provider facts. This is not a reproduced compromised account or mutable native
Mojang-authentication exploit. A separate earlier fixture attempt changed both
UUID and name: Connection Guard's OBSERVE result was ALLOW, but actual LuckPerms
independently refused the UUID whose pre-login data was not loaded. That attempt
is retained as an incomplete/failed fixture, not a successful backend join.

## Reproduction

Use the exact runtime/native JAR hashes in each receipt, JDK 21, the tested guard
JAR and a Minecraft-protocol client supporting 1.21.11. Do not distribute the
runtime/SDK JARs or private keys with the addon. Compile the Java source with
`javac --release 21 -proc:none` against the guard JAR, the named Paper API and its
compile libraries, and actual LuckPerms; package only `fixture/` classes and
`plugin.yml`. Explicit conscious Minecraft-EULA acceptance is required before
starting a backend. The original owner's approval covered only these named,
sequential, isolated loopback fixtures with at most 768 MiB per backend; this
source does not automatically accept the EULA or convey consent to another user.

Prepare a fresh private directory and a random 64-character lowercase hex UTF-8
forwarding secret, never a production secret. Bind both backend (768 MiB maximum)
and proxy (256 MiB maximum) to fresh 127.0.0.1 ports. Disable backend and proxy
account online-mode, enforce-secure-profile/force-key-authentication, metrics,
remote consoles and queries. Configure Paper's `proxies.velocity.enabled: true`,
`online-mode: false` and the fresh secret; configure Velocity `modern` with its
private forwarding-secret file, zero login-rate limit and only the owned backend
in `servers`/`try`. Do not retain unrelated server fallbacks. Ordinary-offline
receipts instead leave native forwarding disabled and use no proxy/key.

Native LP uses H2, `server: forwarding-fixture`, no sync/messaging/watch/translations.
The guard uses SQLite, all built-in detector services off, disabled Geo with
127.0.0.1 and 203.0.113.10 exempt, no VPN exemptions, VPN permission exemption on,
ENFORCE/CLOSED VPN failure and OPEN Geo failure, declared forwarding and Floodgate
off. Enable `identity.paper-modern-forwarding.enabled`. Explicitly select provider
`native-forwarding-fixture` and observer `native-forwarding-observer` in guard
configuration, then reload after the addon registers.

Console `fixture-forwarding prepare` grants CGFwAllowed the native matching server
context and CGFwWrong a different context. Start the Node fixture with arguments
`<owned-loopback-port> [<private-fresh-secret-file>]`; commands are
`connect <synthetic-name> proxy|valid|tamper|missing`, `close <synthetic-name>`, `stop`.
`proxy` uses the actual proxy or ordinary offline backend; raw forwarding modes
use the direct owned backend and fresh key file. Clear each literal guard cache
before its case and compare decision/native/actual-join markers with receipts.
For current-proof checks, use `fixture-forwarding hold`, a valid CGFwWaiting
connection, wait for the actual held provider marker, then `mutate` and `release`;
repeat under OBSERVE. Disable the adapter only after quiescence and reload; retain
native forwarding to prove the disabled adapter cannot promote it. Remove a
manual DENY by its returned rule ID. Stop clients, proxy and backend in finally,
retain private logs/checksums, and require normal process exits.

These receipts do not qualify real accounts, all Paper/Folia builds, legacy
Spigot, Bungee modern forwarding, Floodgate combined with this adapter, a stolen
forwarding key, production detection quality or the complete differentiators.
See [connection identity contract](../../../docs/NATIVE_IDENTITY.md).
