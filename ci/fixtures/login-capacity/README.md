# Login capacity publication regression

A completed login previously published its result before releasing its reserved
capacity. A completion consumer could block this cleanup. The failing CI capacity
assertion was reproduced deterministically by waiting inside a completion consumer
and observing a completed future with one active login slot.

`LoginChecksTest.completedCallerReleasesCapacityBeforeABlockingCompletionConsumer`
and `cancelledCallerReleasesCapacityBeforeABlockingCancellationConsumer` now assert
release before arbitrary consumers execute. The completion case also asserts that
its physically blocked deadline executor cannot be retired by reconfiguration.
Shared source work remains independently bounded and is not cancelled by a caller.
Run the core suite with a real isolated Redis instance; CI retains XML results even
when a build fails. The final suite has 205 tests without failures or skips.

These exact-build receipts requalify the [whole-login budget fixture](../login-budget/)
on Velocity and the [platform action fixture](../platform-tasks/) separately on Paper
1.21.11 build 132 and Folia 1.21.11 build 14, Java 21. Their source hashes identify
those existing fixtures; no new synthetic permission authority is claimed as native.
The [native permission fixtures](../native-permissions/) qualify real LuckPerms
5.5.85 / Floodgate 2.2.5 build 141 on the same guard build.

Use isolated loopback test directories, disabled metrics and synthetic offline
clients. Backend runs require conscious Minecraft EULA approval, at most 768 MiB
heap, one backend at a time and owned shutdown. Velocity uses 256 MiB. These named
receipts prove their reported cases, not authenticated Java/Bedrock identity, all
Minecraft versions, all platforms or detection accuracy. No published JAR changes.
