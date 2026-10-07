# Frozen 0.6.0 manifest compatibility contract

`java/.../IntelSnapshot.java` is byte-for-byte the source from Plugin tag `0.6.0`,
commit `180cebe4afd631c44d61844d6f5c6daf6c6fe941`, SHA-256
`dee70c73b5fa2ec1676c58cf62f67622fe64a955758659da7789878965689185`.
Do not edit it. The separate Gradle source set compiles it against the current
compatible support types. `Intel060CompatibilityTest` uses a child-first loader
only for that class and executes its original `manifest` method on the PROXY
fixture. A hash check prevents silently replacing the legacy validator; the
negative contract also rejects PROXY incorrectly added to the four base lists.

This source set is test-only and excluded from published archives. The fixture
contains synthetic documentation networks and no production signing material.
