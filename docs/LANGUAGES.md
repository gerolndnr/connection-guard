# Messages and languages — development 0.5.0

The development plugin bundles English (`en`), German (`de`) and Spanish (`es`). These are messages for the plugin, not proof of market demand or a translation of every document or third-party addon. Stable 0.4.11 remains separately published.

Set `message-language: de` or `message-language: es` in `config.yml`, then use `/cg reload`. The same selection covers player denial/notification text, classic commands/help, human guidance in doctor/explain/rule/local commands and rich webhook labels. Command names, permissions, paths, placeholders, numeric values and machine facts such as `UNKNOWN`, `DENY`, `VPN_FLAG`, provider IDs and structured counter keys stay unchanged. Missing risk/identity/source data remains unknown in every language. Provider/addon descriptions and general startup/library logs can remain technical English.

## Customization and upgrade

The plugin selects `translation/<message-language>.yml` on every startup and reload. If a bundled locale file is missing, it copies that locale, even if `translation/` already exists. It never replaces an existing file. Missing known message keys fall back to immutable defaults for the selected locale; they are not automatically written to the file. Existing customized templates and lists win, so an older customized help list will need explicit editing to include newer commands.

Use a filename stem of 1–32 ASCII letters/digits/underscores/hyphens, beginning with a letter, without `.yml`, spaces or directories. Built-in selection is case-insensitive (`DE` loads German defaults and selects `DE.yml`). An existing custom stem, for example `community-BR`, keeps its file and uses English for absent keys. A new unknown stem receives an English template; this does not invent a translation. Symlink files/folders, non-files and files larger than 64 KiB are rejected. Message text is bounded; known scalar/list types, supported placeholders and well-formed Unicode are validated before activation.

Old proxy versions copied English under any newly selected filename. If you already have such a `de.yml` or `es.yml`, it is retained to protect your customization. Compare it with the bundled resource; explicitly back up/rename it if you want the new bundled translation copied. Do not delete your operator text as part of an automatic migration.

The original `%NAME%`, `%IP%`, `%ENTRY%` and documented geo/info placeholders remain supported in their corresponding templates. Operational/rich templates use positional `{0}`, `{1}`, etc.; keep their documented slots. Substitutions are literal and performed once. Literal command syntax and status codes are not translated. Rich webhook formatting still omits names/UUIDs/provider free text and requires separate explicit IP opt-in. Legacy TEXT templates can contain the identity placeholders you explicitly configured.

## Rejected reloads

The adapter reads and validates the selected message document and complete provider draft before calling activation. Invalid language/file/template/policy rejects the reload and keeps active messages and security configuration. The rejection uses the currently active language and omits parser contents, paths and exceptions. Correct the files and reload again. A newly missing translation file may be copied while preparing a draft; copying alone does not activate it.

A guard decision captures one immutable catalog at its start, so delayed callbacks and its rich notification do not mix locale changes. Command replies also retain the catalog selected for that operation. A successful reload confirmation uses the newly activated language. Local data refresh retains custom message overrides instead of replacing them with defaults. Locale changes do not change the detection cache namespace or lower the voting minimum.

## Evidence and limits

Shared regression cases cover complete schemas, Unicode, fallback/custom file retention, supported legacy placeholders, literal substitutions, path/symlink/file bounds, invalid message types, captured policy drafts and translated embed privacy/decimal/status/size invariants. [Public native fixtures](../ci/fixtures/languages/README.md) exercise actual supplied Bukkit/Bungee YAML implementations and real Velocity offline login/command/reload paths. These are synthetic qualification cases, not authenticated users, operator pilots, provider accuracy, genuine Discord delivery or all supported server versions.

No new accounts, paid services or automatic language/translation network requests are required.
