# Selected native Bungee qualification — 3 October 2026

This is development evidence for a future feature release. Published 0.4.11 is
unchanged. Exact tested guard JAR SHA-256:
`6848d238f9f98b95c954174a84bf1b3c711c25d1bfcc3e5f833709ea6d9eda83`.
Its embedded 0.4.10 development version is not the published 0.4.10 artifact.

## Actual selected stack and cases

BungeeCord **26.1-R0.1-SNAPSHOT build 2100**, source `5430e4a`, Java **21**,
LuckPerms **5.5.85 build 1672** and Floodgate **2.2.5 build 141**. Each receipt
pins runtime, guard, native plugins, Java fixture sources, compiled addon,
descriptor, local harness and retained private console log. No SDK classes are
bundled into the addon or placed on the proxy parent classpath.

| Receipt | Observed boundary |
| --- | --- |
| [Native gateway](bungee-gateway-2026-10-03.json) | Real native LP matching/wrong server contexts, null UUID refusal, ordinary offline input, actual encrypted canonical native gateway records, linked canonical mapping and alias/wrong-port rejection, tampered encryption, disabled native guard option, manual DENY precedence and native retirement while a provider check waits. |
| [Globally online, per-connection offline](bungee-online-forced-offline-2026-10-03.json) | The same native cases on a globally online proxy; the fixture deliberately sets only its two synthetic ordinary Java names offline. Global online mode and SDK presence cannot create authenticated authority. Floodgate itself handles gateway connections in its native offline path. |
| [Without SDKs](bungee-missing-sdk-2026-10-03.json) | Compile and run without LuckPerms/Floodgate SDKs, with the native guard option enabled; null UUID and declared forwarding trust cannot invent native proof or a permission grant. |

The native `setPendingRemove` case drives Floodgate's actual retirement API on a
record created by its real encrypted network handshake. While the client socket
stays live, native lookup still returns that record but active membership is gone:
new capture and the retained proof fail. ENFORCE temporarily denies and OBSERVE
allows with `IDENTITY_UNAVAILABLE`; neither applies the released provider facts.
This controlled lifecycle call is not a claim about every ordinary disconnect.

Gateway names, XUID and supplied linked mapping are synthetic assertions encrypted
with the fresh owned gateway key. No Xbox/Mojang authentication or real native
account-link registration is performed. There is no fixture-side record insertion,
SDK stand-in, backend server or backend join. A reserved, unused loopback target
prevents contacting another server. Other Bungee/Waterfall versions, live native
authenticated-object mutation, forwarding and real accounts remain unqualified.
The missing-SDK case also has no native SDK on its compile classpath.

The isolated declared-forwarding cases only test permission dispatch and native
contexts. A configuration declaration is not verified forwarding or deployment
advice. Do not install these test addons or enable trust just to fix an exemption.

## Reproduction inputs and procedure

Download from the official sources and check the exact receipt hashes:

- [Bungee build 2100](https://hub.spigotmc.org/jenkins/job/BungeeCord/2100/), artifact
  `bootstrap/target/BungeeCord.jar`.
- [Floodgate build 141 metadata](https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141),
  using its Bungee download. The official SHA-256 was independently matched.
- [LuckPerms metadata](https://metadata.luckperms.net/data/all), using build 1672's
  Bungee loader. Bungee and LP hashes pin our HTTPS-verified downloaded bytes;
  no independent upstream checksum/signature was verified for those two files.

Compile `NativeBungeeFixture.java` and
[the gateway encoder](../native-identity/GatewayHandshakeFixture.java) with
`javac --release 17 -proc:none`, against the exact guard/runtime/native JARs.
Package only `NativeBungeeFixture*.class` and `plugin.yml` renamed to `bungee.yml`;
keep the encoder separate. For the absent-SDK variant compile only
`MissingBungeeFixture.java` against guard/runtime, and package its classes plus
`plugin-missing.yml` renamed to `bungee.yml`.

Use a fresh private owned directory and loopback listener, Java 21 with at most
256 MiB for the proxy, no installed modules, no backend, no live accounts, metrics
off and native LP H2 storage with server context `native-fixture`, sync/watching/
auto-translations off. Disable Floodgate linking/global linking. Disable all guard
built-in VPN providers and Geo, select provider `native-bungee-fixture` and observer
`native-bungee-observer`, SQLite cache, empty VPN exemptions, loopback Geo exemption,
VPN permission exemption on, ENFORCE, CLOSED VPN failure and OPEN Geo failure.
Set native Floodgate on and declared forwarding false; reload after registration.

Console `fixture-bungee prepare` installs actual LP matching/wrong contexts.
Use the encoder modes `plain`, `linked` and `corrupt` with the owned native key,
send each resulting UTF-8 host in a Minecraft login handshake to the loopback port
and compare `BUNGEE_CONNECTION` and `BUNGEE_DECISION` markers with the receipt.
Keep generated host files and keys private. For each native retirement mode, clear
cache, switch ENFORCE/OBSERVE, turn permission exemption off, issue
`fixture-bungee hold`, start a plain gateway login, wait for `BUNGEE_WAITING`, then
`retire-native-record`, `retired` and `release`. Confirm the active/lookup/current/
recapture/live markers and the final `IDENTITY_UNAVAILABLE` decision.

All three complete successful fixtures stopped normally with `end` in cleanup;
private logs remain retained. A first
[harness attempt](harness-before-fix-2026-10-03.json) failed because the driver
expected `Removed` instead of the actual `Rule removed.` command response. That
attempt stopped normally and was not counted as qualification. Only the driver
expectation changed; the complete fresh native runs above then passed. Native
stack fixtures are not automatically run by CI. Existing CI continues to check
core regression tests, reproducible packaging and clean Velocity startup.

See the [identity contract](../../../docs/NATIVE_IDENTITY.md) and
[permission guide](../../../docs/PERMISSION_VALIDATION.md) for remaining scope.
