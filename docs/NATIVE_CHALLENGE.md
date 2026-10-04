# Optional native pre-backend challenge (development)

This is a separately built **development addon**, default disabled. It uses the public MIT LimboAPI interface; passing its map/chat challenge **does not authenticate an account or exempt VPN, Geo, native bans or manual DENY**. Conditional rule-specific verified UUID grants remain separate unfinished work. It is not part of stable 0.4.11 and does not complete the temporary-freedom differentiator.

## Install and select deliberately

Build `./gradlew check shadowJar :adapters:limbo:jar`. Install the combined development JAR and `adapters/limbo/build/libs/connection-guard-limbo-0.1.0-dev.jar` on Velocity. Install the separately licensed native LimboAPI plugin yourself: the selected tested artifact is [Modrinth version r04s3qzv](https://modrinth.com/plugin/limboapi/version/r04s3qzv), descriptor `1.1.27-SNAPSHOT (git-e638f4d)`, SHA-256 `c1e58ce5d4b38c3e1b898cfc70e28a4e0d87240a91f03ffa8adfb183f327d6ce`.

The public API is [MIT licensed](https://github.com/Elytrium/LimboAPI/blob/e638f4d/api/LICENSE); the native plugin is separately [AGPL licensed](https://github.com/Elytrium/LimboAPI/blob/e638f4d/LICENSE). Selected native source commit: `e638f4d8e0ef839a36866a3f1f7814f28f1f07fd`. Neither the native SDK nor this addon is bundled into the combined plugin. The newer `git-bfef579` artifact requires Java25 and was rejected for the selected Java21 environment; do not replace the pinned SDK assuming the same version prefix establishes compatibility. LimboFilter is not used and no claim is made about its private challenge completion.

Start once to create `plugins/connection-guard-limbo/challenge.properties`. Defaults:

```properties
enabled=false
timeout-seconds=30
maximum-attempts=3
maximum-sessions=64
```

Set `enabled=true` and restart to require the challenge. Timeout is 2–120 seconds; attempts 1–8; pending/initial-routing sessions 1–4096. Avoid raising bounds without resource measurements. Unknown/duplicate keys, nonliteral booleans, invalid bounds, oversized or symlinked configuration and missing/incompatible SDK fail closed when the addon is present. Explicit `enabled=false` leaves ordinary access checks active. Removing the addon disables this optional requirement; it is not a core mandatory control. Configuration changes require a proxy restart; the normal `/cg reload` invalidates pending challenges by changing the captured policy object.

A player receives a map showing six random digits and types them in chat. The owning native session's actual `onChat` input must match while the same Player object, full remote socket, live connection, deadline and captured guard policy remain current. Answers are bounded and matched without a server-side success setter. Prompt text is cleared on terminal states and never emitted in addon logs. This is an interactive challenge transport, **not a measured bot-classification or DDoS protection claim**.

The native registered-login callback only queues work before the backend. On success the addon advances the public native flow; final profile processing and the normal guard LoginEvent still apply. Separate LoginEvent and initial ServerPreConnectEvent gates prevent native queue advancement alone from granting initial backend contact. Manual DENY and provider refusal remain effective. OBSERVE skips this addon's enforcement, including releasing a pending challenge after a mode change; release does not manufacture a pass. Deadline cleanup cannot kick a player whose receipt was already consumed by a real ServerConnectedEvent. The [actual backend lifecycle fixtures](../ci/fixtures/native-challenge/backend/README.md) now qualify this on the named Paper/Folia stacks: capacity is freed after actual joins, and both admitted clients remain online past their challenge deadlines.

## Exact current evidence and remaining limits

[Public controlled fixtures](../ci/fixtures/native-challenge/README.md) record five owned sequential Java21/256MiB proxies: Velocity3.4.0 build566 with actual LimboAPI, then four actual no-SDK configurations. Actual synthetic offline Minecraft1.19.2/protocol760 clients receive maps and send chat. Eleven native cases and five absence cases pass, including same-name reconnect replay, finite attempts, expiry, reload, capacity, manual DENY, ordinary VPN refusal, correct continuation and OBSERVE transitions. Provider calls and contacts at an owned loopback TCP sink are counted. The sink intentionally closes; the expected remote-close log is **not a backend join**.

The separate [backend receipts](../ci/fixtures/native-challenge/backend/README.md) add 20 cases and eight actual PlayerJoinEvent-backed joins on Paper 1.21.11 build 132 and Folia 1.21.11 build 14, through native modern forwarding with actual Minecraft 1.21.11/protocol 774 clients. They qualify pending/refusal, capacity consumption, no post-deadline kick, reconnect/reload and OBSERVE lifecycle on those stacks. No genuine Mojang/Xbox authentication, verified conditional TTL grant, all-version qualification, Geyser integration, production detection rate, real operator pilot or performance percentile is proved. Other client/runtime/version combinations are not qualified by this module. The gate requires modern Velocity; Bukkit/Bungee native challenge transports remain open if required by the full compatibility scope. All five full differentiators and the final separately versioned feature release remain outstanding.
