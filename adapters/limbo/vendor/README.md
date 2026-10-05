# Pinned LimboAPI compilation dependency

Coordinates: `net.elytrium.limboapi:api:1.1.26` (63,145 bytes).
SHA-256: `49e4c74df0b0eea87cad4700d3eaf73b3b384c3c6b0e0e61cda462f104c309d5`.

This is the unchanged public API archive previously downloaded from
https://maven.elytrium.net/repo/net/elytrium/limboapi/api/1.1.26/api-1.1.26.jar
and used to compile the released 0.5.0 addon. Its SHA-256 is also present in the
cached upstream Gradle module metadata, checked before copying. On 2026-10-05
the official Maven artifact/POM paths returned 404 and broke clean CI builds.
Retaining the exact MIT API keeps clean builds independent of that outage.
Gradle verifies the file hash before compilation.

Upstream source: https://github.com/Elytrium/LimboAPI/tree/master/api.
The API module is MIT licensed (see `LICENSE` and the upstream API file headers).
This is **only a compileOnly dependency**, excluded from the addon and main JAR.
It contains no native LimboAPI plugin implementation; operators still install
that separate upstream plugin explicitly. It is never an automatic runtime download.
