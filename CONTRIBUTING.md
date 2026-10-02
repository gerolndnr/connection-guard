# Building Connection Guard

Install JDK 8 and JDK 17. Run Gradle with JDK 17; the core, Spigot and BungeeCord modules compile with JDK 8, while Velocity compiles with JDK 17. The included wrapper selects Gradle 8.7 and verifies its distribution checksum.

```sh
./gradlew --no-daemon --console=plain -Dorg.gradle.java.installations.auto-download=false clean check shadowJar
python3 ci/verify_artifact.py
```

The combined plugin is written to `build/libs/connection-guard-<version>-all.jar`. The verifier checks the three platform descriptors and entrypoints, their versions, the modules' class-file versions and the absence of bundled server APIs. These checks do not establish server compatibility, provider accuracy or runtime behavior. The repository currently has no Java unit tests; `check` alone is not a runtime test.

Dependencies use Maven Central, the existing Spigot and Paper repositories, Mojang's official library repository for Brigadier, and AlessioDP's repository for Libby. BungeeCord uses the published Java-8-compatible `1.20-R0.2` API instead of the unavailable `1.19-R0.1-SNAPSHOT`.

The `Build` GitHub Actions workflow runs on pull requests, pushes to `master`, and manual dispatch. It uses a standard Ubuntu runner, read-only repository permissions and pinned actions. It verifies the wrapper before running it, rebuilds the archive to compare SHA-256 checksums, and uploads the checked JAR and its checksum for seven days. Archive entries use a stable order and fixed timestamps. CI artifacts are development builds, identified by their source commit; they are not published stable releases, even when their embedded version matches an existing release.

Before publishing a release, align the Gradle project version and Velocity's `@Plugin` version, run the build and artifact verifier, complete the appropriate server/proxy runtime checks, review changes and document compatibility and upgrade notes. Publishing to GitHub or a plugin directory is a separate step.
