# Native IPQS runtime fixture

Unreleased development qualification only. The console-only addon selects the native
adapter through its existing package-private loopback constructor, preserving configured
native source IDs, options, budgets, health counters and namespace. No production endpoint
setting is added; the original artifact is unchanged. A synthetic-key assertion prevents
this fixture from using an operator credential. Never install on a production proxy.

The owned driver uses Velocity 3.4.0 build 566 / JDK 21, loopback HTTP and synthetic
offline logins, SQLite and the actual provider/observer classloader. Its nonlistening
backend port is owned; passing a login is not a backend join or independent account login.
No real IPQS account, upstream call or measured detection accuracy is claimed.
Driver: `tools/release_ipqualityscore_velocity_test.py` in the authorized operations workspace;
it requires existing pinned host/runtime helpers and is not a standalone clean-host runner.

Ten named actual cases and exact hashes: [runtime-receipt.json](runtime-receipt.json).
Public driver snapshot: [driver.py](driver.py); it matches the authorized local driver.
