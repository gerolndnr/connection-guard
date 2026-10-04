# Actual challenge-to-backend lifecycle evidence

These locally authored MIT fixtures qualify the **unchanged development** main JAR and separate optional challenge addon through actual native LimboAPI and Velocity to the named approved Paper/Folia servers. They are not production clients or operator pilots.

| Stack | Result | Actual cases | Actual backend joins |
| --- | --- | --- | --- |
| Velocity 3.4.0 build 566 / LimboAPI git-e638f4d → Paper 1.21.11 build 132, Java 21 | [Paper receipt](paper-2026-10-04.json) | 10 | 4 |
| Same proxy/native SDK → Folia 1.21.11 build 14, Java 21 | [Folia receipt](folia-2026-10-04.json) | 10 | 4 |

Every owned process exited 0 and was stopped before the next backend began. Backend limit: 768 MiB; proxy: 256 MiB. All listeners and provider responses are loopback only; bStats and native update checks are disabled. Clients are synthetic offline Minecraft 1.21.11/protocol 774, with a fresh private Velocity modern-forwarding MAC per run. Forwarded offline UUIDs are **gateway delegation, not genuine Mojang/Xbox authentication**. No real account token or challenge-success setter is used.

The client receives actual map pixels, the independent [decoder](../MapDigits.java) solves them, and actual chat input passes the native owned session. [Backend event listener](CGChallengeBackendFixture.java) records actual `PlayerJoinEvent` and `PlayerQuitEvent`; it cannot mark a challenge passed or grant access. Presence probes read its event map, without querying another Folia entity from the console thread. Backend joins require the expected synthetic UUID; a proxy LoginSuccess packet alone never counts.

Cases cover pending/disconnected, three wrong answers, pending expiry, normal VPN refusal after a solved challenge, real backend admission, capacity consumption, no delayed kick after both observed challenge deadlines, same-name reconnect with an old answer, policy reload while another challenge is pending, and OBSERVE admission. Capacity is one: a second client receives a new map while the first actual backend player stays connected. Both remain in PLAY and in the backend event map after the six-second deadlines plus 1.25 seconds. This qualifies receipt cleanup on the actual routing transition for these stacks. It does not measure bot classification, p50/p95, load limits, all versions or grant/account authority.

The exact [operational driver snapshot](operational-driver.py) is retained here. On the authorized operations host, its executable counterpart is `tools/release_native_challenge_backend_test.py`, with existing `Backend`, `Proxy`, packet-client and Java/runtime helpers whose hashes are in each receipt. It requires that local runtime layout and the consciously approved backend scope. It is **not a standalone clean-host CI runner**; that remains open for the full quality differentiator.

Example invocation from the operations root, after verifying the named official runtime/SDK hashes, owning the loopback endpoints and consciously accepting the Minecraft EULA for this isolated scope:

```sh
python3 tools/release_native_challenge_backend_test.py --fixture <fresh-run-name> --platform paper --artifact repository/build/libs/connection-guard-0.4.10-all.jar --sha256 23d3849c0a6bdcd4b0210db6ba18724be9382634416502fd1195eba6849fd5fa --addon-sha256 6e678dd85c2371f129edd4d967bc2b248186794f1eb32b7380363c0df51076b4
```

Run Folia separately with `--platform folia` and a new unique directory, only after the Paper process has stopped. Never point the clients at production. The driver verifies every selected runtime/JAR hash and refuses a reused directory. Private forwarding secrets are not included in receipts or logs; actual local logs are bound by SHA-256. Generated backend-fixture ZIP timestamps may differ between runs; these are not plugin release archives.

[Failed preparations](failed-attempts-2026-10-04.json) preserve the compile dependency issue and the pinned Velocity NONE spelling mismatch. The latter started/stopped the owned Paper backend but no proxy/client or challenge join. Neither is counted as a passed runtime case; corrections change only fixture preparation, with no production change or weakened assertion.

Stable 0.4.11 is unchanged. Independently current verified accounts, rule-specific conditional TTL grants, further runtime/version combinations, full clean-host automation, real voluntary setup cases and the separate final feature release remain open.
