# Localization feature

`feature.localization` / `LocalizationFeaturePlugin` owns the dictionary provider,
language settings form, and optional default UI projection in one install/enable
boundary. Its internal provider supplies `KcodeLocalization` through the neutral
`TranslationCatalog` contract; its projection binds the same catalog to
`ApplicationSlots.Localization`. The default application borrows an optional catalog and
uses page/feature-owned English defaults when it is absent. Headless Goal commands,
schedule dispatch and native Android conversation overlay declare their own localization
dependencies. Disabling the provider suspends those consumers and removes its language
settings/UI binding; the core settings page remains usable. Missing UI services suspend only the UI children;
the dictionary remains available for headless consumers. The former
`provider.localization.default` and `consumer.localization.ui` releases migrate together,
retaining the old group when needed by an external dependency and preserving disable
choices and failed-commit rollback.

Shared retains opaque `AppLanguage` identifiers, stable `LocalizedText` keys, locale
metadata and an explicit `LocalizationContext`. Its undefined language marker is `und`;
it has no Chinese/English default dictionary or resource lookup. Provider/model display
helpers use only the committed model metadata and the active locale's declared fallback
order. New locale codes do not require changing an enum or rebuilding shared.

The XML dictionaries in `src/main/localization` compile into private Kotlin bytecode in
actual JAR/APK artifacts. They are not looked up through the host Compose resource table.
The provider owns its available languages, default language (Chinese), fallback language
(English), template formatting and all product translations. UI language settings render
the provider's list. Headless Goal/schedule calls and export status use the injected catalog.

The provider accepts `Unit` for existing defaults, or a JSON object:

The package's `src/profile-export/feature.localization.json` declares portable Unit and
dictionary JSON configuration. Export permits only default/fallback language, language
labels and translation maps with bounded string values. Unknown root fields and non-text
dictionary values are denied. Runtime validation still checks locale declarations and
translation placeholders; schema review does not start a localization provider.

```json
{
  "defaultLanguage": "fr",
  "fallbackLanguage": "en",
  "languages": {"fr": "Français"},
  "translations": {"fr": {"new_chat": "Nouvelle conversation"}}
}
```

Unknown fields, malformed locale identifiers, undeclared languages, non-string labels,
unsupported format placeholders and changes to existing keys' argument contracts are
rejected before replacing an active mount. Missing entries use only the active provider's
configured fallback language. Unknown keys fail. Supported format tokens are indexed
`%1$s`, `%1$d` and escaped `%%`; integer arguments must be finite whole numbers.

The catalog owns no asynchronous work. Its available state becomes false on disposal;
strict `translate` calls reject stale references. Composition-facing snapshots and text
lookups return null during retirement so teardown cannot revive the dictionary. Published
metadata is copied to protect internal policy from consumer mutation.

Common tests verify existing labels/formats, custom locales, fallback choices, invalid
configuration and retirement. Desktop JAR and Android APK tests verify private dictionary,
configuration and formatter identities, actual Goal command translation, invalid replacement,
dependency suspension, restart/re-enable and uninstall. Actual application scene/window tests
replace the composer label and verify withdrawal and restoration across the package boundary.


The private dictionaries currently contain 174 keys in each language, including native
approval, generation foreground notifications, scheduled notification channels and overlay
controls. Default native entries explicitly consume Localization and Settings; independently
String-configured native entries use their supplied text without those dependencies.

The same feature JAR/APK contains its default settings form. The feature entry mounts an
optional settings contribution as a child Fiber; missing `uiSlots` suspends only this
child. Disabling/replacing the feature withdraws its setting item, without withdrawing
the settings page. Restoring the feature registers a fresh contribution. There is no
separate UI-only settings package.

Rendering `displayText` returns null for unknown keys as of API 51, allowing a
consumer to use its own defaults. Strict `translate` still rejects unknown keys
and withdrawn catalogs. The default shell can survive feature withdrawal using
page/feature-owned texts; no replacement headless service is implicitly installed.

API 56 exposes optional `TranslationCatalog.languageSettings: LanguageSettingsPolicy?`.
Rendering-only catalogs return null. The dictionary feature owns a revocable policy that
interprets `feature.localization` JSON (`language` string), resolves available choices and
uses its configured dictionary default for missing or unsupported preferences. The language
form is visible only when the active catalog supplies that configuration capability.
The default root borrows this projection and does not decode language persistence fields.

With no namespace, the feature reads its raw historical key from `legacyValues`. Updates write only the
namespace, preserving unknown fields, unrelated feature data and the legacy migration input.
An existing empty namespace uses the dictionary default without recovering the legacy value.
Unknown stored locale identities remain stored while rendering uses the provider's fallback.
Updates reject undeclared locales; retained policies reject work after catalog withdrawal.
Root text projection closure releases its own view without closing a borrowed language policy.

API 59 removes fixed legacy fields and storage product defaults. Language, execution and
permission configuration belong to their features; durable UI validation remains tracked in
the plugin audit. Rebuild packages for ABI 59; existing localization SDK exports cover this
neutral interface, with no private policy implementation exported.

API 60 registers feature-owned namespace validation with `KcodeSettings.mutations`.
The default root uses `KcodeSettings.mutationStore`: proposals merge into the latest
transaction state before validation, and the registration lifetime covers durable save.
Withdrawal rejects subsequent edits of this namespace while preserving its saved data;
other active settings owners remain usable. Validation is independent of settings-command
and default UI availability. Feature rules validate types and newly selected values while
retaining unchanged future identities and unknown document fields.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- feature.localization

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
