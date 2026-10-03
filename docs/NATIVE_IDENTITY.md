# Connection identity and optional native Floodgate records

Development contract for the next feature release. Published 0.4.11 remains unchanged.

Connection Guard distinguishes a platform's per-connection Java authentication, an
operator's declared forwarding trust, a legacy Bukkit global online-mode flag, and
an explicitly enabled native Floodgate gateway record. UUID shape, a name prefix,
installed classes, `isFloodgatePlayer`, or `getPlayer` alone do not establish a
current connection. Declared forwarding and global online mode retain existing
ordinary UUID/permission exemptions but are not verified authority for future
conditional temporary grants.

```yaml
identity:
  trust-forwarded-uuid: false
  floodgate:
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
proof for that flag. True direct Java/backend authentication and the selected
Bungee native runtime still need separate qualification.

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
