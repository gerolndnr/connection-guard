# Network cache and per-server policy

Development verification for the next release. Redis is an optional shared raw-fact cache;
SQLite remains available for one server. Neither cache stores a final access decision.

Two Connection Guard processes using the same detection configuration and source generations
derive the same SHA-256 cache namespace. A detected IP's complete raw VPN/Geo answer can be
reused across those processes until its cache TTL and source validity both permit reuse.
Manual and metadata access rules are evaluated separately on each server at decision time;
changing an access rule does not inherit another server's final denial or grant.

Changing enabled detection providers, provider version/credential/endpoint/mapping, voting
threshold, or active local data generation derives a different namespace. Existing entries
remain in their original namespace and expire by Redis TTL; they do not become results in the
new configuration. Login admission counters, provider budgets, managed rules and permissions
are local to each plugin process. Shared cache is not shared global quota, rule replication,
cross-server rate limiting or reliable ban/alt-identity messaging.

```yaml
provider:
  cache:
    type: Redis
    expiration:
      vpn: 1440
      geo: 4320
    redis:
      hostname: cache.internal
      port: 6379
      username: connectionguard
      password: '<your secret, never include in a diagnostic report>'
      tls: true
```

Use your own protected Redis service and review its access control. Hostname accepts a DNS
name or literal address, without a URL scheme; port is 1..65535. The configured ACL username
and password are passed independently to Jedis. Remote TLS uses the JVM trust store; install
the appropriate trusted certificate rather than disabling certificate checks. The runtime
fixture described below uses loopback plaintext and does **not** establish TLS deployment
compatibility. There is no bundled Redis server or new paid service requirement.

For a scoped ACL user, the plugin needs PING, GET, SETEX, DEL and SCAN and access to the
`cg:v2:*` keys belonging to your installation's detection namespaces. SCAN with a matching
prefix clears only the active namespace/scope; no FLUSHDB/FLUSHALL is used. Clearing active
facts is visible to another process sharing that namespace. Unrelated keys and other
configuration namespaces remain. A finite 1000-page scan can report an incomplete clear;
do not interpret that as guaranteed atomic deletion under concurrent writes. Redis expiry
also bounds old namespaces even when no explicit clear occurs.

One serial connection and a queue of 64 operations bound pending cache work. Connect/read
timeouts are one second. Operations capture their namespace, write TTL and plain payload
before queueing, so a later setting change cannot write into the wrong configuration.
Invalid, mismatched or stale data is a cache miss. Reads on cache outage fall back to the
bounded detection path; write failure does not reverse a positive detection. Cache failure
alone does not imply that a connection is safe. Provider failure policy still governs an
unknown detection response. Serialized exceptions use CACHE_ERROR without backend credentials.

An initial cache connection must succeed before checks enable. Cache connection/type/ACL/TLS
changes require a restart; a reload draft preserves the active settings if such a change is
attempted. Detection configuration changes can reload only under the quiescent lookup contract.

Evidence:

- Real Redis integration tests cover identical-namespace IPv6 facts, scope-specific clear,
  TTL and expiry, isolated namespaces, queued-operation capture, source freshness, malformed
  data, explicit ACL authentication and redacted invalid credentials. CI runs Redis 7.2.12.
- A controlled fixture ran two **real Velocity 3.4.0 build 566 / Java 21** processes against
  an owned loopback Redis 7.2.12 service with the same plugin JAR. It verified shared cache
  reuse without a second detection HTTP call, per-server rules, changed endpoint namespace
  isolation, TTL/invalid-payload refresh, unrelated-key preservation and Redis restart recovery.
- That fixture had no Minecraft backend, did not test TLS or production infrastructure, and
  does not prove all declared Minecraft/platform versions. Synthetic facts are not an accuracy
  or security-audit result. Full artifact/version evidence belongs to the release quality matrix.

MySQL remains the original analysis's conditional parity item: no confirmed need has been
recorded. The verified SQLite/Redis choices cover local and shared raw facts. This condition
must remain explicit in the completion audit; do not silently claim a MySQL adapter exists.
