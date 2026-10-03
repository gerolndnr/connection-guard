# Native encrypted gateway identity qualification — 3 October 2026

These Java/Node fixture sources run only on isolated owned loopback servers.
They are not production addons. `NativeIdentityFixture` uses actual installed
Floodgate/LuckPerms APIs and the versioned guard provider/observer API.
`GatewayHandshakeFixture` encrypts synthetic gateway data with the fresh owned
Floodgate key. It never inserts a player record: the actual Floodgate network
handshake creates the record. Keep the key and generated host files private and
out of the repository; SDK JARs are also not redistributed here.

The unlinked XUID and supplied linked Java UUID/name are synthetic gateway
assertions. No Xbox/Mojang authentication or real native account-link lookup is
performed. The fixture-controlled `setPendingRemove` drives the actual native
retirement API on a record previously created by its real encrypted handshake;
it does not fabricate an authenticated session. Ordinary Velocity disconnect
removes the record fully in this selected version. Pending-removal lookup is
therefore qualified separately by that controlled native lifecycle call.

Exact development artifact SHA-256:
`8254d278e67d848facffa7250f07e5446345a76069e9f3b8e408bbb6e4e22a65`.
This artifact is not a new published release. Named receipts:

- [Velocity](velocity-2026-10-03.json): native current full socket/canonical mapping,
  linked alias rejection, tampered encryption, actual native LP grant, disabled
  guard option, two encrypted connections replacing the native record, pending
  removal during ENFORCE/OBSERVE, rejected reload followed by quiescent disable.
- [Paper](paper-2026-10-03.json) and [Folia](folia-2026-10-03.json): actual modern
  connection metadata, current canonical native proof, real LP contexts, successful
  offline backend join under synthetic gateway delegation, wrong-context denial,
  disabled native option and manual DENY precedence.

- [Velocity without SDK](velocity-missing-sdk-2026-10-03.json) and
  [Paper without SDK](paper-missing-sdk-2026-10-03.json) and
  [Folia without SDK](folia-missing-sdk-2026-10-03.json): the native option is on,
  but actual SDK classes are absent, and no permission/native exemption appears.
- [Whole-login budget regression](login-budget-velocity-2026-10-03.json): 250 ms
  caller budget still clips permission and combined waits; authority in this
  separate timing fixture is explicitly a synthetic API stand-in, not native LP.

All owned processes stopped normally. Other platform versions, real Bedrock/Java
accounts and real account linking are explicitly untested by these receipts.

## Reproduction inputs and procedure

Download and verify the exact native JAR/runtime hashes listed in the receipts.
Official Floodgate build metadata:
https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141 .
Official LuckPerms metadata: https://metadata.luckperms.net/data/all .
Compile against the tested guard JAR, native JARs and the selected platform API;
use `javac --release 17 -proc:none` for the Velocity Java sources and `--release 21`
for the backend source. Package only fixture classes plus the corresponding
`velocity-plugin.json` or `plugin.yml`; never package SDK classes.

Use a fresh isolated loopback directory, native LP H2 storage/server context
`native-fixture`, disabled metrics/linking/global-linking, offline platform auth,
SQLite guard cache, all built-in detector sources disabled, Geo disabled with
loopback exempt, VPN permission exemption on, empty VPN exemptions and ENFORCE
with CLOSED VPN failure. For Velocity select `native-identity-fixture` and
`native-identity-observer`; the backend source selects `native-perm-fixture` and
`native-perm-observer`. Enable `identity.floodgate.enabled` and leave declared
forwarding false. Reload after the addon registers.

Run the handshake encoder with three arguments: owned key path, a fresh private
output path, and `plain`, `linked` or `corrupt`. Send the resulting UTF-8 host in a
Minecraft login handshake while the TCP destination stays 127.0.0.1. The backend
Node source uses minecraft-protocol's `fakeHost` with offline 1.21.11 and has no
account login/cache. Its arguments are loopback port, installed minecraft-protocol
module path and the three private host files. Console `fixture-native prepare`
sets matching unlinked and wrong linked LP server contexts; the Velocity console
uses `fixture-identity prepare`, `hold`, `release`, `retire-native-record` and
`retired`. Compare complete decision markers with the named receipt cases. Stop
clients and the owned runtime in finally, retaining private console logs.

Paper/Folia require conscious EULA acceptance before starting. The original owner
approved only the named local sequential loopback fixtures, at most 768 MiB each.
This fixture is not enabled automatically in CI and does not convey EULA consent
to another operator. The native guard implementation is described in
[NATIVE_IDENTITY.md](../../../docs/NATIVE_IDENTITY.md).
