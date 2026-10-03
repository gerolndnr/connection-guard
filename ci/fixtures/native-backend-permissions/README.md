# Actual native permission contract on Paper and Folia

These test-only plugins exercise Connection Guard with actual LuckPerms 5.5.85
Bukkit and Floodgate 2.2.5 build 141 Spigot on the named Paper/Folia runtimes, Java 21.
They register synthetic detector facts; permission decisions come from the installed
LuckPerms implementation. No native API is installed separately or bundled in the
addon. Player accounts are synthetic offline accounts, not authenticated identities.

## Reproduce the named cases

1. Consciously accept the [Minecraft EULA](https://www.minecraft.net/en-us/eula) for
   the local fixture. Use a new private test directory, loopback-only bind, disabled
   RCON/query, a small flat world and offline clients. Run Paper 1.21.11 build 132 or
   Folia 1.21.11 build 14 with Java 21 and at most 768 MiB heap, one backend at a time.
   Verify the runtime and guard hashes recorded in the corresponding receipt.
2. Download the native plugins from their official versioned HTTPS endpoints and
   verify the pinned hashes in each receipt. LuckPerms:
   `https://download.luckperms.net/1672/bukkit/loader/LuckPerms-Bukkit-5.5.85.jar`.
   Floodgate: `https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141/downloads/spigot`.
   The Floodgate hash matches official build metadata; the observed LuckPerms hash
   is pinned over verified HTTPS, without a separately verified upstream signature.
3. Compile `CGNativeBackendFixture.java` with `javac --release 21 -proc:none`
   against the exact guard, Paper API and compile dependencies, and the actual
   LuckPerms/Floodgate JARs. Package only `fixture/*.class` and `plugin.yml` in the
   addon. Install both actual native plugins. Their descriptor dependency chain
   exposes canonical native APIs through the real Bukkit plugin classloaders.
4. Configure LuckPerms with `server: native-fixture`, `storage-method: h2`,
   `messaging-service: none`, `sync-minutes: -1`, disabled file watching and disabled
   translation installation. Disable all bStats metrics. Disable Floodgate own/global
   account linking and metrics; generated keys remain in the private fixture directory.
5. Select Connection Guard provider `native-perm-fixture` with voting enabled and
   observer `native-perm-observer`; reload after the addon registers. Disable built-in
   VPN providers and Geo, select SQLite cache, exempt Geo for `127.0.0.1`, clear VPN
   exemptions, enable VPN permission exemptions, disable staff/actions/webhooks,
   select ENFORCE with CLOSED VPN / OPEN Geo, and leave forwarding trust false.
6. Run console-only `fixture-native missing-id` (grant=false), `status` (api=true,
   Floodgate membership/player=false for an unregistered UUID), `prepare`
   (allowed=true, wrong=false, staticServer=true), and `unloaded` (before=false,
   grant=false). Prepare saves native permission nodes for `CGNativeAllowed` in
   `server=native-fixture` and `CGNativeWrong` in `server=different-fixture` before
   querying the actual guard helper. The initially unloaded user receives no grant.
7. Clear localhost cache before each login. `CGNativeAllowed` must initially be
   rejected as VPN_FLAG/POSITIVE/UNTRUSTED despite its native permission. Only for
   this isolated declared-trust test, select `identity.trust-forwarded-uuid: true`
   and reload: the matching user joins with CHECKS_COMPLETE/EXEMPT/FORWARDED;
   `CGNativeWrong` is still denied as VPN_FLAG/POSITIVE/FORWARDED.
8. Add `cg deny add 127.0.0.1 vpn 5m Synthetic native permission precedence`.
   The matching native-permission user is denied by ACCESS_RULE with NOT_CHECKED
   detector status. The native grant cannot override the manual deny.
9. For the missing-SDK case use a separate new backend directory with no native
   plugins or API JAR. Compile `CGMissingBackendFixture.java` against guard/Paper
   dependencies only and package `plugin-missing.yml` as `plugin.yml`. Keep the same
   guard source/observer selection. Availability must be false and null UUID must
   grant nothing. With explicitly declared trust, `CGNativeMissing` remains denied
   by VPN_FLAG/POSITIVE/FORWARDED rather than receiving an invented exception.
10. Stop clients and the owned server; require exit status 0 and no plugin enable,
    classloader or platform-thread failures. Never use these addons in production.

Each receipt records exact guard/native/addon/source/runtime hashes. Successful
matching-context admission includes a real offline backend join. Manual declaration
of forwarding trust remains a declaration; these cases do not qualify cryptographic
forwarding, authenticated Java/Bedrock sessions, linked accounts, older Spigot,
BungeeCord, all Geyser behavior, detection accuracy or a complete version range.
The [Velocity native cases](../native-permissions/) qualify a separate stack.

Floodgate's [official API documentation](https://geysermc.org/wiki/floodgate/api/)
allows membership/player queries before login and explains that proxy-to-backend
API data requires matching keys and enabled data forwarding. This fixture has no
proxy or authenticated Bedrock session; an empty native record is tested explicitly.

Named receipts: [Paper native](paper-native-2026-10-03.json),
[Folia native](folia-native-2026-10-03.json),
[Paper missing SDK](paper-missing-sdk-2026-10-03.json), and
[Folia missing SDK](folia-missing-sdk-2026-10-03.json).
