# Current platform connection binding — 3 October 2026

This is development evidence for a future feature release, not a new published
version. Exact qualified artifact SHA-256:
`6848d238f9f98b95c954174a84bf1b3c711c25d1bfcc3e5f833709ea6d9eda83`.

## What changed and what the tests establish

Velocity and Bungee capture canonical UUID, name and full resolved client socket
from the same native login connection. The authenticated proof re-reads those
values, that connection's authentication mode and live state. A post-capture
change or failed read invalidates the proof. The listener passes the captured
identity to subsequent permission/rule checks and actions. Bukkit still supplies
no new direct Java authentication proof in this package.

The [old core reproducer](AuthenticatedIdentityBeforeFix.java), compiled against
base `60fa243480ed5038c7ba2ea3887dfded2436eed4` and its previous JAR, changes
synthetic mode/UUID values while keeping the supplied live flag true. The old
boolean API cannot observe them and incorrectly retains verified authority;
[its receipt](before-fix-2026-10-03.json) records expected exit code 3. This is a
core-contract regression, **not a reproduced mutable-account exploit in a native
proxy or a real authenticated session**. Do not compile this historical source
against the new probe signature. The current core tests cover initial binding,
null/offline/disconnected input, failed reads and UUID/name/socket/mode/live-state
changes, including a different client port on the same IP and borrowing a proof
for unrelated resolve inputs. All 238 core tests pass with real local Redis and
zero skipped tests; nine packaging/release guards pass.

## Actual runtime regressions on this exact artifact

| Receipt | Actual observed boundary |
| --- | --- |
| [Velocity gateway](velocity-gateway-2026-10-03.json) | Native encrypted synthetic gateway assertions, canonical linked mapping/full socket, real native LuckPerms context, record replacement and pending-removal during waits, OBSERVE, rejected active reload, disabled option and tampered encryption. |
| [Paper gateway](paper-gateway-2026-10-03.json) / [Folia gateway](folia-gateway-2026-10-03.json) | Native encrypted synthetic gateway assertions, real native LuckPerms contexts, actual backend joins, wrong context, disabled option, ordinary offline input and manual DENY precedence. |
| [Velocity online with offline connection](velocity-online-forced-offline-2026-10-03.json) | The actual global online-mode proxy and test addon's deliberate per-connection offline override do not establish authenticated authority; ordinary checks and native permission contexts remain correct. |
| [Velocity without SDKs](velocity-missing-sdk-2026-10-03.json) | Enabled native option without installed LuckPerms/Floodgate cannot invent native proof or a permission grant. |

Use the existing [gateway sources and procedure](../native-identity/README.md)
and [native permission sources and procedure](../native-permissions/README.md)
with this exact new guard JAR. Their original receipts remain unchanged and pin
their original older artifacts. New receipts pin their runtime, native plugin,
source and compiled addon hashes. The sources are unchanged; compile only against
installed SDKs and package fixture classes/descriptors alone. Keep owned gateway
keys and generated encrypted hosts private. Paper/Folia were run sequentially,
only on loopback, with the owner's recorded EULA decision and at most 768 MiB each.
All owned clients and runtimes stopped normally.

These runtime regressions do **not** test a genuine Java/Xbox login, real native
account-link registration, a real authenticated platform object's mutation,
verified backend forwarding, Bungee runtime or legacy Spigot. The authentication
probe's positive/invalidation cases use synthetic core metadata; the native
runtime cases above qualify gateway delegation and negative authentication
boundaries. Bungee is compiled here but still needs selected native runtime
qualification. A Paper field called `authenticatedProfile` alone is insufficient:
offline verification also fills it, and direct native authentication sets it after
pre-login in the selected implementation. No backend account proof is inferred.

The [identity contract](../../../docs/NATIVE_IDENTITY.md) and
[permission evidence guide](../../../docs/PERMISSION_VALIDATION.md) explain
operator trust and the remaining account/platform/temporary-grant scope.
