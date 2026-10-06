# Native tool approvals

`provider.tool-approvals.native` publishes `KcodeToolApprovals`. Native builds distribute
the dual-target package with `ai.meteor.kcode.plugin.LocalizedNativeToolApprovalPlugin`
and `Unit` configuration. It injects settings and localization services, acquires an
effect-owned `PluginHostInputs` lease and delegates dialogs to `ConfirmationDialogHost`.
Dictionary ownership remains with localization providers; window mechanics remain in the SDK.

`NativeToolApprovalPlugin` supports explicit portable JSON text through `String` configuration.
`toolApprovalConfig` constructs `title`, `message`, `allow` and `deny` fields. Titles format
one tool-name argument, and messages format description (falling back to the tool name)
and input, bounded to 2,048 and 8,192 characters. Invalid positional string formats or
missing/blank text fail validation before replacing a mounted provider.

The provider cancels and joins pending approvals before releasing its lease. Retained
approvers reject calls after withdrawal. A host without dialog capabilities cannot mount
this provider. Native Android factories omit it when an explicit custom approver is supplied,
preserving the existing policy boundary. Other native compositions offer it with or without
default product plugins; missing settings/localization services leave it Pending.

The implementation is excluded from host runtime dependencies. Production-classpath JAR
and real APK tests exercise localized request delivery through a recording SDK dialog adapter,
withdrawal and fresh recovery. Separate lifecycle tests verify cancellation/cleanup waiting
and replacement rollback. Recording adapters do not establish real user permission consent.

API 59 localized approval requests resolve the committed snapshot through
`TranslationCatalog.configuredLanguage`; this delegates to the dictionary's optional
configuration policy. The provider never decodes a persisted language key. Rendering-only
catalogs use their declared default. Private JAR/APK assertions distinguish a namespace
language from a conflicting historical preference while preserving withdrawal/recovery.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.tool-approvals.native

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
