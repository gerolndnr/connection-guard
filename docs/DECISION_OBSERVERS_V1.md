# Decision observers, contract v1

An addon can observe Connection Guard's actual result at a named login phase.
Observers receive immutable data after guard processing; they cannot change an
event, a permission, an exemption or the admission result. Include the combined
plugin as a compile-only dependency and declare the real platform plugin dependency.
Do not shade another copy of the API.

```java
ObserverRegistration handle = ConnectionGuardApi.registerDecisionObserver(
    "my-observer", observation -> recordLocally(observation));
```

Registration is installed but inactive. An operator explicitly selects trusted
addon IDs, then reloads:

```yaml
integrations:
  observers:
    enabled: true
    ids: [my-observer]
```

IDs use `[a-z][a-z0-9-]{0,31}`; at most eight can register or be selected. A missing
module receives no callbacks. Registering a late module or replacing a closed
registration requires another complete reload. Closing an old handle cannot
remove its replacement. Observer selection is independent of provider selection
and does not invalidate cached detection facts.

## Data and interpretation

Each observation includes platform, phase, captured operation mode, actual guard
ALLOW/DENY/ERROR outcome and a typed summary reason. It carries normalized literal
IP, decision timestamp and elapsed guard-processing time. An untrusted offline UUID
is withheld. AUTHENTICATED denotes the per-connection platform authentication
choice. FORWARDED denotes the explicitly configured forwarding declaration;
PLATFORM_ONLINE denotes the legacy Bukkit global flag, and FLOODGATE denotes
an explicitly enabled, current socket/canonical-record gateway proof. Declarations
and global flags are not verified session authority. A lost current proof yields
IDENTITY_UNAVAILABLE, also when OBSERVE allows the connection. See
[NATIVE_IDENTITY.md](NATIVE_IDENTITY.md) for the selected native contract and named
synthetic gateway qualification. This observation is an immutable snapshot, not an
authorization token for a challenge grant or a ban.

VPN/Geo check states distinguish NOT_CHECKED, EXEMPT and UNKNOWN. An exemption
does not produce a synthetic negative source. Sources preserve status/reason,
metadata, source version/expiry, voting eligibility, duration and cache provenance.
Provider AUTHENTICATION failures refer to provider credentials, not player identity.
Geo source records are non-voting; their successful facts use NEGATIVE in the shared
source status model, not a claim that the client has no VPN. Geo check state KNOWN
indicates that geographic facts were available.

Rule records contain evaluated scope, stable rule ID, effect, actual MATCH/MISS/
UNKNOWN/CONFLICT and selection. A selected conflicting deny stays CONFLICT. The
rule evaluator's DENY/ALLOW/EXEMPT precedence is included. Read this rule evaluation
together with exemption states, mode and effective outcome: a selected metadata
rule alone is not the complete platform decision. Positive facts in OBSERVE can
accompany ALLOW. Raw endpoints, provider payloads, exception text, command senders
and player/world objects are omitted.

Velocity can reject a literal network rule during PRE_AUTHENTICATION without
requesting detection. Its identity is untrusted and checks remain NOT_CHECKED.
The ordinary eligible login phase is LOGIN. Other plugins may reject a connection
before the guard runs or after its ALLOW result. This API reports the guard's result
at its named phase, rather than asserting a completed backend join.

Mode and failure choices are captured for each login; overload uses that same mode.
Facts are copied at evaluation time. Whole-policy configuration versioning, replay,
shadow comparison and atomic rollback remain separate work.

## Dispatch and lifecycle

Callbacks use two dedicated daemon workers and a queue of 64 jobs. Each selected
receiver costs one queue job. Full queues drop observations; publishing never uses
the caller as an overflow worker. Callback exceptions and observation-construction
failures increment a redacted failure count without changing admission. `/cg stats`
and `/cg doctor` include registered/selected counts, queued jobs, deliveries, drops
and failures. There is no delivery/order guarantee, automatic persistence or replay.

Reload keeps the same pool, discards queued old-generation observations and prevents
old captures from reaching newly selected code. Closed handles suppress callbacks
that have not started. Already executing callbacks can finish. Disabling observer selection in configuration
discards queued jobs but retains registrations and the bounded pool. Plugin shutdown
interrupts workers and clears registrations. Activation after shutdown is rejected
until old workers have terminated, so reinitialization cannot create replacement
pools around blocked callbacks. Trusted addons share the JVM; these bounds are not a sandbox.

An observer thread is not a platform/player/world thread. An addon must dispatch
any platform work through that platform's supported scheduler. Do not perform
blocking calls or create bans solely from provider flags. Close the registration
when the addon disables. Default configuration has observers disabled.

## Qualification

Core tests cover explicit selection, missing/late/closed/replaced modules,
immutable copies, hidden untrusted UUID, callback errors, finite queue rejection,
generation changes, captured mode, raw conflicting/unknown rule evidence and
shutdown with a callback that ignores interruption.

The [synthetic unshaded addon](../ci/fixtures/decision-observers/README.md) exercises
real login events and the actual classloader on pinned Velocity. Exact JAR/source/
addon/runtime hashes and results are stored beside its source. Its acceptance is
limited to its named version, offline synthetic clients and tested paths. Bukkit/
Bungee adapters compile against their APIs; authenticated identities and other
platform runtimes require their own proofs.

Rich built-in [decision webhooks](WEBHOOKS.md) use an independent sender and do not require addon observer selection. Their rendering/transport failures cannot prevent a separately selected observer from receiving the original immutable decision. OBSERVE keeps addon observation active while suppressing built-in webhook output.
