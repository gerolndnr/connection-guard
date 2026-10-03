# Synthetic Paper/Folia platform fixture

Source-only, unshaded addon for an isolated loopback backend. **Never install on
production.** It depends on Connection Guard and is compiled against the combined
JAR and the pinned Paper API; it does not carry a second copy of plugin classes.
Synthetic offline clients and a loopback HTTP provider exercise actual login,
cache and command paths. Their labels are not an accuracy benchmark.

Only start a Minecraft server after consciously accepting its EULA. The qualified
scope uses Java 21, at most 768 MiB, one backend at a time, separate fresh server
directories, loopback addresses, RCON/query disabled and bStats submission disabled.
Do not expose this offline test server to other hosts.

Compile in a fresh directory against locally verified JARs:

```sh
javac --release 21 -proc:none -cp "$CG_JAR:$PAPER_API_CLASSPATH" -d classes PlatformContractFixture.java
jar --create --file fixture.jar -C classes . -C . plugin.yml
```

Configure only the synthetic custom provider, ENFORCE, VPN failure CLOSED,
SQLite cache, geo disabled with the loopback address exempted, and the on-flag
console command `fixture-platform action`. Other provider integrations stay off.
The addon attaches synthetic command/notification permissions to joined clients.
All controls are console-only:

```text
fixture-platform targets
fixture-platform metrics
fixture-platform probe
fixture-platform action
fixture-platform status
fixture-platform retired
```

`targets` checks literal IPv6 and propagation of a consumer failure without
reinterpreting it as an address-parse failure. `metrics` invokes the dispatcher
and collector consumers actually supplied to pinned bStats MetricsBase 3.0.2 via
reflection, from a separate worker. It checks global ownership and expected
aggregate fields, without calling submission or sending telemetry. This deliberately
pinned test reflection is not an API for third-party integrations.

After the staff client joins, `probe` checks global ownership and entity ownership,
and runs player `/cg doctor`, `/cg info <name>`, `/cg clear <UUID>` and an asynchronous
reply. With geo disabled, information must display UNKNOWN. Change the synthetic
provider to positive and clear the IP cache: the next login must be denied,
`action` must run globally, and staff must receive its notification through its
entity context. A following login reuses cached facts without another HTTP request.
Reload OBSERVE: a flagged login may join without another action. After disconnect,
`retired` must never execute the captured player's callback. Check zero errors
and stop the backend and every fixture process.

Each acceptance record contains the actual plugin/runtime/addon/source hashes and
tested cases. Qualification applies only to those versions and paths. It does not
prove authenticated identities, legacy Spigot, other Minecraft versions, or real
bStats HTTP submission.
