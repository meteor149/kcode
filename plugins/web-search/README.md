# Web Search feature

`feature.web-search` is the single Unit-configured entry point:
`ai.meteor.kcode.plugin.websearch.WebSearchFeaturePlugin`.
One package owns HTTP search, the `web_search` model tool, search configuration,
validation/command handlers and the optional default settings section. Disable or
uninstall the package to withdraw all of them together. Saved credentials remain in
`AppSettingsStore` and are reused on recovery.

The implementation uses owned child Fibers. HTTP requires `KcodeSettings`; the tool
requires `KcodeTools` and `KcodeWebSearch`; commands require `KcodeSettingsCommands`;
the settings form requires the default UI contracts and search policy. Missing optional
consumers' dependencies suspend only those children. The generic settings page has no
reference to this feature and remains usable after its withdrawal.

The backend routes Google, Exa and Bright Data requests. Desktop packages carry private
Ktor/CIO dependencies; Android packages carry private Ktor/OkHttp dependencies. Both
variants include configuration policy and Compose settings implementation. SDK identities
are supplied by the host. In-flight searches are cancelled and joined before clients
close; retained backend, policy and settings callbacks reject work after withdrawal.

The former `search-settings` and `web-search-provider` modules are consolidated here;
they have no independent distribution entries. Verified old bundled releases migrate to
the aggregate, preserving an explicit disabled or uninstalled state.

Settings English defaults live in `src/main/ui-texts/strings_en.xml` and compile
into this feature's private bytecode. They register with the section and disappear
with it; the page does not supply this feature's field labels or read host resources.

Search persists its feature-owned document under `feature.web-search`:
`provider` is a string and `apiKeys` is an object mapping provider identities to strings.
The feature chooses Google when no provider is configured and validates owned value types.
Unknown document fields, unrelated namespaces and credentials for unknown routes are
preserved. The storage provider no longer chooses a search route.

When this namespace is absent, the policy interprets the legacy search fields and keys.
The first configuration update writes a complete feature document, retaining legacy
raw values for compatibility migration. When the namespace exists, missing owned fields use
feature defaults; legacy credentials are never resurrected. Empty credential strings stay
empty. Corrupt owned values fail rather than silently reverting to legacy settings.
Selection validation and document writes serve both commands and the form. The form trims
only the selected credential, leaving credentials for other routes untouched.

API 59 reads historical search keys from opaque `legacyValues`; this feature owns the
legacy Bright Data route inference when a credential exists and no route was supplied.
Storage performs only historical name translation and never selects a route. Fixed SDK
members and product defaults are removed. Validation ownership through the complete UI
durable transaction remains tracked in [the plugin audit](../../docs/plugin-feature-audit.md).

API 60 registers feature-owned namespace validation with `KcodeSettings.mutations`.
The default root uses `KcodeSettings.mutationStore`: proposals merge into the latest
transaction state before validation, and the registration lifetime covers durable save.
Withdrawal rejects subsequent edits of this namespace while preserving its saved data;
other active settings owners remain usable. Validation is independent of settings-command
and default UI availability. Feature rules validate types and newly selected values while
retaining unchanged future identities and unknown document fields.
