# bStats

Connection Guard uses the platform's standard bStats integration for aggregate installation/platform statistics. The project IDs are **22911** (Bukkit), **22912** (BungeeCord) and **22913** (Velocity). The [Velocity project identity](https://bstats.org/api/v1/plugins/22913) is Connection Guard, owner `gero`, software ID 6.

The Velocity adapter after 0.5.0 initializes the bundled, relocated bStats 3.0.2 `Metrics.Factory`, creates the Metrics instance with ID 22913 once at startup and shuts it down with the plugin. Initialization/shutdown failure does not stop connection checks. Reload does not create another Metrics instance. The Velocity SDK and its base classes must pass the combined-JAR packaging checks; loading a library alone does not register a project.

No custom chart or player identity data is added. Standard SDK data includes aggregate player/backend counts, online mode, plugin/Velocity/Java versions, OS/core details and a bStats server UUID. bStats receives the reporting server's network connection. These statistics are separate from [Connection Guard Cloud](CLOUD.md); disabling Cloud does not disable bStats.

To opt out on Velocity/BungeeCord, edit the generated `plugins/bStats/config.txt` and set `enabled=false`, preserving its other fields. Bukkit uses the generated `plugins/bStats/config.yml`. Restart the proxy/server. These global bStats settings apply to other plugins using the same platform config too.

The local Velocity fixture proves the actual injected SDK instance, ID 22913, disabled config, real platform/service collectors, reload and clean shutdown. It does **not** submit telemetry or confirm production ingestion. Public counts can lag and cover reporting instances only; they must not be added across platforms or called market share.
