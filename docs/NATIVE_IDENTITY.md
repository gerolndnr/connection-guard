# Connection identity and optional native Floodgate records

Development contract for the next feature release. Published 0.4.11 remains unchanged.

Connection Guard distinguishes a platform's per-connection Java authentication, an
operator's declared forwarding trust, a legacy Bukkit global online-mode flag, and
an explicitly enabled native Floodgate gateway record, and a separately labelled
native modern-forwarding gateway assertion on the named Paper/Folia builds. UUID shape, a name prefix,
installed classes, `isFloodgatePlayer`, or `getPlayer` alone do not establish a
current connection. Declared forwarding and global online mode retain existing
ordinary UUID/permission exemptions but are not verified authority for future
conditional temporary grants.

For a proxy-authenticated connection, the current development adapters bind a
proof to the same native connection's captured UUID, exact canonical name and full
resolved client IP/port. They re-read its UUID/name/socket, per-connection online
mode and live state after waits; a changed value or failed read removes verified
authority. Global proxy online mode cannot substitute for that per-connection
mode. Permission/rule work and actions use the captured canonical identity. This
binding primitive is implemented on Velocity/Bungee; positive genuine account
logins still require their own qualification. Selected Bungee gateway and negative
authentication boundaries are qualified separately below. The synthetic
core mutation regression is not evidence of a native mutable-account exploit.

```yaml
identity:
  trust-forwarded-uuid: false
  floodgate:
    enabled: false
  paper-modern-forwarding:
    enabled: false
```

Enable native Floodgate deliberately after installing the canonical Floodgate plugin,
protecting its gateway key, and protecting forwarded backends from direct access.
This does not configure Geyser, authenticate an Xbox account, validate account
linking, or prove correct proxy forwarding. A trusted gateway can assert identities
using its key; a stolen key or untrusted gateway invalidates that trust boundary.
No upstream API classes are bundled. Bukkit/Bungee soft dependencies and the optional
Velocity dependency permit the installed native API to be resolved. The release
packaging gate rejects root or relocated copies of Floodgate/LuckPerms SDK classes.

For the selected native API, a login captures the canonical `getCorrectUniqueId`
and `getCorrectUsername`, a resolved client IP **and port**, the same native record
object, membership in the active collection, and the connection's live state.
Linked records use their canonical Java mapping; their unlinked alias cannot match
that canonical proof. `getPlayer` also exposes pending-removal records in the
selected SDK, so membership in the active collection is required. Collections are
bounded to 65,536 records; an excessive/failed API probe supplies no native proof.

The proof is checked again after permission/admission/provider waits and before
applying facts, exemptions, flag actions or admission. Replacement by another
handshake, record retirement, disconnect, native API replacement, or an inactive
native setting invalidates it. ENFORCE refuses temporarily with
`IDENTITY_UNAVAILABLE`; OBSERVE allows and records the same reason without flag
commands/webhooks. Manual DENY still takes precedence. The native inspection time
is included in the existing whole-login budget and observation duration.
A reload while login/provider work is active is rejected by the existing runtime
quiescence contract: the active configuration remains intact. Apply the disabled
setting after quiescence; a rejected draft does not silently disable anything.

Modern selected Paper/Folia supply their current client socket/live state through
`PlayerConnection`. Missing legacy connection APIs supply no native proof and keep
ordinary checking available. A Bukkit global online flag is labelled
`PLATFORM_ONLINE`; this package does not claim a current per-connection Mojang
proof for that flag. Genuine direct Java/backend account authentication still needs separate
qualification. Selected native modern-forwarding gateway assertions are qualified
separately below.

## Named qualification

[Native fixture sources and receipts](../ci/fixtures/native-identity/README.md)
use Floodgate 2.2.5 build 141, LuckPerms 5.5.85, Velocity 3.4.0 build 566,
Paper 1.21.11 build 132 and Folia 1.21.11 build 14. Actual native encrypted
handshakes with synthetic gateway assertions exercise canonical records, socket
binding, real native permission contexts and actual Paper/Folia backend joins.
The Velocity fixture exercises native retirement/replacement while checks wait,
including OBSERVE and rejected reload behavior. Tampered encryption cannot reach
the guard's login authority. There is no fixture-side player insertion or SDK
stand-in, and no native SDK placed on a proxy parent classpath.

These are synthetic **gateway delegation** tests. They are not real Xbox/Mojang
logins, real account-link registration, Geyser client-stack qualification, legacy
Spigot/Bungee runtime qualification, or all-version compatibility evidence.
The five complete differentiators, challenge/ban integrations and final feature
release remain separate required work.

The [current platform-binding regressions](../ci/fixtures/authenticated-identity/README.md)
pin a newer development JAR: 238 core tests with real Redis, nine packaging guards,
selected native gateway regressions on Velocity/Paper/Folia, global-online with an
actual offline connection, and absent SDKs on Velocity. The older receipts above
are preserved with their original artifact hash. Neither package supplies a new
Bukkit direct Java/verified-forwarding adapter or genuine account login evidence.

## Selected native Bungee qualification

[Three Bungee receipts and reproduction sources](../ci/fixtures/native-bungee/README.md)
qualify BungeeCord 26.1-R0.1-SNAPSHOT build 2100 / Java 21 on the newer artifact
above: real LuckPerms 5.5.85 contexts, actual encrypted Floodgate 2.2.5 build 141
canonical records, alias/wrong-port and tamper rejection, disabled native option,
manual DENY and native retirement while the same socket remains live. ENFORCE and
OBSERVE recheck native authority after provider waits. A globally online proxy
with deliberately offline synthetic ordinary connections remains untrusted; the
absent-SDK variant creates no native proof or permission. These are synthetic
gateway delegation and negative authentication tests. No backend joins, genuine
accounts, real account-link registration, native authenticated-object mutation,
verified forwarding or other Bungee/Waterfall versions are established.

## Optional native modern-forwarding gateway assertion

`identity.paper-modern-forwarding.enabled` defaults to false. Its native adapter
accepts only Paper 1.21.11 build 132 / commit c5eb079 and Folia 1.21.11 build 14 /
commit 529aabc, using public `ServerBuildInfo` and connection APIs. A missing API,
different implementation/build or failed probe supplies no verified forwarding
proof. Ordinary configured/global trust retains its separate legacy provenance.

On these inspected native implementations, the authenticated-profile field is
assigned **before this exact native asynchronous pre-login phase** only after
the actual modern-forwarding MAC gate accepts the signed canonical profile and
forwarded IP. The ordinary offline and direct-Mojang paths assign that field
after this event. The adapter requires the qualified build, actual native login
connection class, current native profile, matching canonical event UUID/name/IP
and a live full connection socket. The public profile field alone on an arbitrary
server/version or at another phase is insufficient. No private NMS fields, stack
traces or operator configuration flags substitute for the native proof.

Provenance is `VERIFIED_FORWARDING`, distinct from `AUTHENTICATED` and the
operator-declared `FORWARDED`. It verifies the selected native gateway handoff,
**not independent account ownership**. An offline or compromised trusted proxy
can assert identities with its forwarding secret. Protect that key, configure
proxy account authentication according to your policy, and prevent direct backend
access. Enabling this guard option does not configure Velocity or a firewall.

The captured canonical UUID, name and complete native client socket are re-read
after waits, along with event-profile agreement, connection liveness and the
active guard setting. Paper preserves the backend TCP source port while replacing
the client IP from the signed payload; this is not proof of the original external
player port. A changed event profile, disconnected connection or disabled setting
removes current authority. ENFORCE refuses with `IDENTITY_UNAVAILABLE`; OBSERVE
records that reason without lookup facts or flag actions. Manual DENY precedes
ordinary permission exemptions. The whole-login budget includes native inspection.

[Selected native forwarding sources and receipts](../ci/fixtures/native-forwarding/README.md)
cover actual Velocity-to-Paper/Folia joins and MAC rejection, actual LuckPerms
contexts, event-profile name mutation during lookup, disabled-option and ordinary
offline boundaries. Genuine Mojang/Xbox accounts, other builds/forks, and native
Floodgate combined with this new adapter remain outside these receipts.
