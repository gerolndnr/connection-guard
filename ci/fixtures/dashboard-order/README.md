# Actual-JAR dashboard provider-order contract

Six Java8 cases compile against the actual shaded archive and use only private
files and an owned127.0.0.1 HTTP endpoint. They cover snapshot-based gating,
partial order/unlisted sources/IP-API-last, unknown-source refusal, empty order,
IPQualityScore key redaction and actual cached-negative invalidation after a
changed effective chain with preserved local config. CI retains bound receipts.
No production Cloud/provider contact, independent accuracy/performance or account
qualification follows from these cases.

`../cloud/provider_order_native.py` is a workspace-specific owned native driver
for Bungee2100, Paper1.21.11-132 and Folia1.21.11-14/JDK21. It uses pinned helper/
runtime/source hashes in the enclosing ownership workspace. It checks real native
sync snapshots, two custom loopback providers, hot order apply/denial, `/cg cloud
settings`, reload retention, specific unknown-order failure and overlay rollback,
valid empty-order/cache-namespace restoration, explicit clear and clean shutdown.
Synthetic clients only; tests stop each process before the next one. Backends use
at most768MiB and require the operator's conscious EULA acceptance.

These are not real Discord/account authentication or operator pilot cases. The
initial fixture assumptions about `%NAME%` substitution and a compulsory fresh
request when returning to an already-valid namespace were corrected without
changing product behavior. Different-chain cached negatives must not be reused;
same-chain valid evidence remains useful until expiry/explicit invalidation.
