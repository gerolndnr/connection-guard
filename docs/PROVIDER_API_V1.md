# Versioned provider API v1

Development implementation for the next release. This document specifies the provider
contract, validated with the synthetic real Velocity addon linked below.
Decision observers have a [separate v1 contract](DECISION_OBSERVERS_V1.md);
read-only ban checks use the separate [admission API](ADMISSION_API.md) and optional
[LibertyBans addon](../adapters/libertybans/README.md). Actual challenge routing/completion
and broader native qualification remain distinct unfinished work.

Use `com.github.gerolndnr.connectionguard.api.v1`. Compile against Connection Guard as a
compile-only dependency and declare your platform's dependency on the Connection Guard plugin.
Never shade another copy of Connection Guard or its API classes into an addon. Check
`ConnectionGuardApi.CONTRACT_VERSION == 1` before registration. The contract is Java 8;
a Velocity addon still uses its platform's required Java version.

```java
ProviderDescriptor descriptor = new ProviderDescriptor(
    "example", "1.0.0", configurationSha256, true);
ProviderRegistration registration = ConnectionGuardApi.registerProvider(descriptor, ip -> {
    // ip is a normalized literal address. Use actual source facts; this example is synthetic.
    return CompletableFuture.completedFuture(DetectionObservation.unknown(
        DetectionObservation.Reason.NO_EVIDENCE));
});
// On addon shutdown: registration.close();
```

`configurationSha256` is a lowercase 64-character SHA-256 hash representing the detection
configuration. Change it when endpoints, field mappings, data/credentials or detection
semantics change; do not place a raw endpoint or credential in a descriptor. IDs are lowercase
`[a-z][a-z0-9-]{0,31}`; versions are public `[A-Za-z0-9_.-]{1,64}` labels. Labels are operator
visible, so choose public names. Voting intent must match the explicit operator configuration.
The fingerprint and declared version are assertions supplied by the trusted addon, not a
cryptographic audit of its code or detection quality.

Registering installed code does not activate it. The operator chooses selected IDs and local
budgets in the complete configuration draft, then uses `/cg reload`:

```yaml
integrations:
  providers:
    enabled: true
    sources:
      - id: example
        voting: true
        daily-budget: 100
        minute-budget: 10
```

Defaults are disabled and an empty list. At most eight providers may register and at most
eight may be selected; all native, local and extension sources together remain limited to
16. Duplicate IDs, unsupported source fields, invalid budgets or a voting declaration
different from the registered descriptor reject a draft before activation. A selected,
missing addon produces UNKNOWN/NO_PROVIDER without spending a provider request. This lets
an addon register after the guard initializes; the operator reloads after registration.
The requested quorum still includes selected voting sources and is never lowered because
one is absent. Nonvoting enrichment supplies metadata for explicit rules, not a generic vote.

Each selected adapter captures a registration. Closing its handle is idempotent and does not
remove a later replacement with the same ID. New queries cannot reuse cached negatives or
positives while a selected module is missing/closed; they perform the bounded remaining
checks and keep the missing source UNKNOWN. A callback completing after its registration
closes cannot publish its late positive/negative. Already completed in-flight observations
are not retroactively assigned to a replacement. Re-registering requires another complete
reload to select the new adapter. Descriptor/configuration changes derive a new detection
namespace; opaque callback objects and plugin instances are never serialized into it.

`DetectionProvider.lookup` is called on the existing bounded invocation workers. It must
return a non-null `CompletableFuture<DetectionObservation>` and must not access player/world
state on that thread. Blocking or never-completing addons consume only the configured bounded
worker slots and receive the shared lookup deadline; this is trusted same-process code,
not a sandbox that can forcibly kill arbitrary Java. Use your own bounded transport, close
response bodies, and do not start unbounded threads or child requests. Caller cancellation
must not cancel another caller's shared request. Existing same-IP coalescing, queue limits,
provider budgets and failure/circuit handling apply to selected adapters.

Results are immutable and have POSITIVE, NEGATIVE or UNKNOWN status. NONE is required for
a binary status; UNKNOWN requires a specific non-NONE reason. Failed/partial source data
is never NEGATIVE. Return unknown with a typed reason rather than transmitting exception text.
Transport/authentication/invalid-response reasons enter the normal health/circuit path.
NO_EVIDENCE may retain actual enrichment metadata; missing classifications, ASN, country,
risk and confidence remain absent. Risk/confidence are source-reported 0..100 values, not
calibrated abuse probabilities. Hosting/country/ASN alone does not imply generic VPN abuse.

The additive `DetectionMetadata.withExactRisk(..., BigDecimal risk, Integer confidence)`
factory and `getExactRisk()` preserve decimal source risk. Existing integer constructors
and `getRisk()` remain available: the latter returns null for fractional values, never a
rounded/truncated score. Legacy integer observations/caches retain their exact value.
Risk must be 0..100, with bounded precision/exponent; confidence retains its integer contract.
Use `getExactRisk()` for source risk comparisons and in observers. No missing value becomes zero.

`DetectionMetadata` copies its type map, validates fields and exposes an unmodifiable view.
IPv4-mapped IPv6 is normalized to IPv4; no DNS lookup is performed for provider input.
An observation may include `validUntil` (absolute UTC epoch milliseconds) and an actual
data-generation SHA-256 `sourceVersion`. Expired source facts cannot gain freshness from
cache TTL or be used at the final login decision. With no source expiry, ordinary configured
cache TTL still applies; an addon must not claim that missing provenance establishes current
accuracy. Source IDs are stable `extension.<id>` labels without positional suffixes and
can be referenced by source-specific risk/confidence rules.

`/cg providers` and `/cg doctor` show selected registration state, declared version/fingerprint
and voting role without callback internals. Successful quiescent draft activation removes
health entries for unselected sources, bounding histories across changing IDs while retaining
budgets/counters of still-selected sources. Cache connection changes still require restart.

Contract tests cover immutable fields, invalid descriptors, explicit activation, normalized
input, stable attribution, bounded registry/selection, quorum, enrichment-only evidence,
descriptor fingerprints, opaque callback serialization, real SQLite reuse and closed-cache
invalidation, close during pending response, replacement handles and bounded reload health.
The [synthetic addon fixture](../ci/fixtures/provider-api/README.md) also tests the real
Velocity 3.4.0 build 566 classloader, explicit activation, missing/closed module handling,
SQLite reuse, replacement, rejected drafts, budgets, 32 coalesced actual login attempts,
deadlines and typed failures. This qualifies that platform/version and development artifact;
it does not establish backend joins, authenticated identities or other platform compatibility.

## Additive identity provenance

The v1 decision observation can add enum values as new explicitly qualified
identity sources are introduced. Consumers must handle unknown/additional values
without converting them into account-authentication authority.
`IdentityTrust.VERIFIED_FORWARDING` identifies a current selected native gateway
assertion, distinct from `AUTHENTICATED` and operator-declared `FORWARDED`. It is
not independent Mojang/Xbox ownership or a completed challenge. See
[NATIVE_IDENTITY.md](NATIVE_IDENTITY.md) for its build, phase and key boundaries.
