# Lookup runtime lifecycle

Lookup flight completion does not mean that a third-party supplier has stopped
running. A deadline can publish UNKNOWN while an interruption-ignoring supplier still
occupies a worker. Runtime retirement therefore tracks queued/running jobs, their
completion callbacks and scheduled/running deadline callbacks.

An unchanged limit set reuses the existing runtime. It does not cancel pending
permission timeouts or create another executor. A limit change is rejected until all
old jobs and deadlines have settled. The idle check and retirement exclude late
submissions; a call racing with retirement receives CANCELLED or a rejected deadline
registration. LuckPerms registration failures return no exemption.

Provider draft activation also checks active runtime work before changing settings.
If a reload is rejected, release/fix the blocking addon or wait for finite work to
settle, inspect `/cg stats` workers/queue, then retry. Repeated reloads are not a way
to obtain more workers around a stuck addon. Already running trusted addon code is
not a JVM sandbox and cannot safely be forcibly killed.

Shutdown cancels queued/running transport futures and interrupts workers. Existing
finite deadline callbacks remain scheduled to settle owners such as a pending
LuckPerms permission request. New submissions are rejected. A closed runtime cannot
be reinitialized while its old worker/timer executors remain live. A later user load
cannot grant a timed-out permission; loaded-by-helper users are cleaned up on completion.

The core regressions reproduce lost permission timeouts and replacement around an
expired but blocked flight, then cover cancellation, shutdown, queued jobs, executing
timer callbacks, blocked completion callbacks and addon linkage failure. The actual
[Velocity addon fixture](../ci/fixtures/runtime-lifecycle/README.md) qualifies repeated
same/changed reloads and recovery after a blocked supplier releases. Its named proof
does not qualify authenticated identities, Floodgate or an entire end-to-end login
budget; those require their own integration work.
