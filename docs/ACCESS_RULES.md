# Managed access rules

Development feature for the next release; not present in the existing 0.4.10 tag.

The same store also accepts [source-specific metadata selectors](RICH_RULES.md) for ASN, ISP/operator, classification, country and scores. Address/identity overrides precede metadata; the detailed uncertainty rules are documented there.

The same commands work on Spigot, BungeeCord and Velocity:

```text
/cg allow add 192.0.2.0/24 vpn 2h Temporary lab access
/cg deny add 2001:db8::/32 all 7d Laboratory subnet restriction
/cg exempt add 069a79f4-44e9-4726-a5be-fca90e38aaf5 geo 15m Verified identity fixture
/cg allow list
/cg deny remove <rule-id>
/cg exempt remove <rule-id>
```

Examples are synthetic documentation addresses, not real operator recommendations. `add` requires a literal IPv4/IPv6 address, CIDR or canonical UUID, a scope (`vpn`, `geo`, `all`), a duration (`s`, `m`, `h`, `d`, up to a year, or `permanent`) and a reason. No unverified player-name lookup. Permission nodes are `connectionguard.command.allow`, `.deny`, `.exempt`; they cover only that effect's additions/removals/list. UUID matching requires authenticated/trusted identity as described in [operations](OPERATIONS.md).

Precedence per scope: **explicit DENY > ALLOW > EXEMPT > ordinary provider/country rules**. An explicit denial in either scope denies the connection. Allow/exempt skips only the selected scope's external lookup. Global OBSERVE suppresses enforcement of denials. A narrow UUID allowance does not defeat a broad manual denial: remove/change the conflicting denial explicitly. Expired rules cannot affect decisions; the serialized record can remain for administrative review until removed. Within the same effect, the first matching stored rule explains the result; duplicate effects do not alter the access outcome.

Networks with host bits are canonicalized to their subnet. IPv4-mapped IPv6 addresses match IPv4. Mapped CIDR prefixes `/96..128` become IPv4 `/0..32`; wider mapped ranges are rejected. Native IPv4 and IPv6 families are otherwise distinct. Invalid addresses and hostnames never trigger DNS resolution.

Rules are stored in the private `access-rules.json` file in the plugin data directory. Limits: 512 rules, 1 MiB file, 200-character reason without control characters. Lists display at most 20 active entries. Each update validates the complete draft, writes and syncs a temporary file, and atomically replaces the original before activating an immutable snapshot. An invalid reload or failed write retains the previous rules. Symbolic-link rule files are rejected. Manual file edits should be made while the server is stopped; commands update the active snapshot immediately and persist across restarts. Cache clearing is unnecessary because manual access rules are evaluated before cached intelligence/provider calls.

Public rejection text does not disclose the operator's private reason or target. Scope-specific UUID and expiry behavior is tested; authenticated challenge-result/rule-specific integration is delivered separately, without a permanent automatic bypass.
