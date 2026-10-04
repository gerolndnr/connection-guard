# Native language qualification

`driver.py` is a public standalone Python 3.10+ runner using PyYAML, an installed JDK 21 and the sibling public webhook fixture sources. It imports no private ownership-workspace routines. Supply an already acquired Velocity 3.4.0 build 566 JAR (SHA-256 `fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e`), a verified development plugin and a fresh directory:

```sh
python3 ci/fixtures/languages/driver.py --artifact /absolute/plugin.jar --sha256 <actual-sha256> --proxy /absolute/velocity.jar --java /absolute/jdk21/bin/java --work /absolute/fresh-directory
```

It compiles an unshaded synthetic versioned provider addon, explicitly activates its selected provider after registration, starts a 256 MiB offline loopback proxy with telemetry disabled and uses Minecraft 1.19.2/protocol 760. The reserved backend socket never listens. It exercises English/German/Spanish help/actual VPN denial, operational text, preserved partial overrides, unknown custom-locale fallback, path/type/size/symlink rejection, whole policy preservation and valid recovery. Eleven cases must pass; it stops its proxy and closes the reserved socket even on assertion failure. Velocity/libby may download their declared existing runtime dependencies. No real Discord/webhook or IP-detection upstream is configured. No backend joins or real account authentication are claimed.

`NativeLanguageParsers.java` directly calls the supplied Bukkit and Bungee YAML implementations through the same parser entry points used by the adapters. With the development plugin plus compatible provided API/YAML/Guava dependency JARs on the classpath, compile it with JDK 21 and run `NativeLanguageParsers /absolute/fresh-directory`. Six cases per parser cover all bundled locale files, list/scalar messages, partial override retention/fallback and actual invalid YAML/types. Pin and record the supplied dependencies in the receipt. This is native parser evidence, not a Bukkit/Bungee server/login test.

The receipt is bound to the exact main archive, fixture/helper sources, pinned runtime and supplied parser dependencies. Earlier failed attempts remain in the private delivery ledger with redacted causes; assertions must not be weakened to claim success. The general cross-platform/language/latency quality contract remains open.
