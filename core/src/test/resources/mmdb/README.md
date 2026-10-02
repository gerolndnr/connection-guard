# Versioned MMDB fixtures

These are **synthetic MaxMind test databases**, not a production GeoIP database or detection-accuracy dataset.
Copyright (c) 2013–2026 MaxMind, Inc. The upstream repository offers Apache-2.0 or MIT;
we distribute these test fixtures under MIT, with the full `LICENSE-MIT` alongside them.

`manifest.json` records the exact upstream Git commit, source URLs, byte counts and SHA-256 hashes.
Normal fixtures exercise actual IPv4/IPv6 country and ASN decoding. The two denial-of-service fixtures
must only be decoded through `BoundedMmdb`, which caps depth, value count and copied payload before allocation.
They demonstrate bounded rejection, without expanding their advertised data. They are excluded from the plugin JAR.

Upstream format and resource-limit guidance:
https://github.com/maxmind/MaxMind-DB/blob/276926d23b4109ca5452709bfb5931c338afb34c/MaxMind-DB-spec.md
https://github.com/maxmind/MaxMind-DB/blob/276926d23b4109ca5452709bfb5931c338afb34c/test-data/README.md

`BoundedMmdb` is Connection Guard's independently implemented focused reader. It does not bundle the upstream
Java reader. The stricter supported limits/formats and the distinction between imported data licensing and test
fixture licensing are documented in `docs/LOCAL_DATA.md`.
