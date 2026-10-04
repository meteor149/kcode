# Localization providers

`provider.localization.default` supplies `KcodeLocalization` through the neutral
`TranslationCatalog` contract. `consumer.localization.ui` binds the same catalog to
`ApplicationSlots.Localization`. The default application, localized page contributions,
settings, Goal commands, schedule dispatch and native Android conversation overlay declare
localization dependencies. Disabling the provider suspends consumers and removes its UI
binding; no host dictionary is restored.

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
