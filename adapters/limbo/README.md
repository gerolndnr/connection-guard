# Connection Guard native challenge addon (development)

Separate MIT module using only compile-only public LimboAPI1.1.26. Native runtime is provided by a separately installed AGPL LimboAPI plugin; no SDK, core or server classes are packaged here. Default disabled; enabled missing/incompatible SDK or invalid configuration fails closed at login and initial backend routing. See [installation, boundaries and actual evidence](../../docs/NATIVE_CHALLENGE.md).

A completed challenge only continues normal guard checks. It cannot authenticate UUIDs, bypass manual DENY or native bans, or grant VPN/Geo exemptions. Verified, rule-specific temporary grants remain unfinished. Stable0.4.11 and all five full differentiators are unchanged by this development module.
