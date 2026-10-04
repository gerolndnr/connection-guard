# Connection Guard 0.5.0 qualification

The final combined JAR is built from the 0.5.0 release source. It includes the bStats hotfix, validated rules/providers/messages and the native Cloud bridge. Before publication the operations release gate requires a clean source commit, successful exact-source CI, package verification and hash-bound clean Paper and Velocity startup/help/reload/shutdown evidence.

Selected native release fixtures cover Paper 1.21.11 build 132, Folia 1.21.11 build 14, BungeeCord build 2100 and Velocity 3.4.0 build 566 on Java 21. Controlled loopback providers and synthetic offline clients check observation, enforcement, whole-draft rejection, local configuration/translation preservation, reset, persistent Cloud disable and independent login behavior while Cloud stalls. Absolute dashboard rule deadlines are checked across a stalled Cloud connection, including a delayed already-expired command. The Cloud browser/API fixture uses an immutable copy of the Dashboard source, local dev authentication and synthetic processing terms.

The source APIs still target Java 8 on core/Spigot/Bungee and Java 17 on Velocity. Platform runtime Java requirements apply independently. Selected Paper/Folia checks do not prove all legacy Spigot or other Folia versions. Proxy fixtures do not prove complete authenticated backend journeys. Native permission/identity/addon evidence in the other guides retains its own exact original artifact hashes.

No live provider accounts, Mojang/Xbox accounts, genuine pilot operators or production player data are used for these fixtures. They establish behavior for controlled facts, not detection accuracy, market share or load capacity. The Cloud 24-hour load test remains separate future work. Each release download has a SHA-256 checksum; optional addons are separate artifacts with their own license and installation requirements.


## Additional current-version startup qualification

The published 0.5.0 artifact (SHA-256 `172d6e4dc7b925efc8498cab46f28807dba47f20750fbfbdb3c56a64002bb503`) also passed isolated startup, help, reload and shutdown checks on Paper 26.3 build 151, Folia 26.2 build 7, Velocity 4.2.0 build 30 and Velocity 4.2.1-SNAPSHOT build 36 with Java 25. Paper and Folia builds were upstream beta builds; Velocity 4.2.1 is a development snapshot. These checks extend startup coverage, not the scope of the older login fixtures or a claim that every intermediate version was tested.

Download and docs: https://connectionguard.net/download
