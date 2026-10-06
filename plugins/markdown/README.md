# Markdown feature

`feature.markdown` / `MarkdownFeaturePlugin` owns formatting and its optional UI projection
in one install/enable boundary. Its internal `MarkdownProviderPlugin` supplies the
`KcodeMarkdown` / `MarkdownContent` contract.
Block parsing, inline styles, plain-text projection, palette and Compose rendering live in
this module's private namespace. `MarkdownUiContributionPlugin` independently binds the
active provider to the committed `content.markdown` slot; it never constructs a fallback.
Missing UI services suspend only the projection, leaving formatting available to headless
consumers. The former `provider.markdown.default` and `consumer.markdown.ui` releases
migrate together, preserving disable choices and failed-commit rollback. External package
dependencies retain the old group and suppress aggregate activation until migration is possible.

Messages consume the slot, image export and scheduled notifications inject the service.
Provider withdrawal removes the slot and suspends the latter consumers. Retained formatting
references reject new calls; retiring UI frames render nothing. Formatting computations are
pure and own no IO, threads or coroutines. Remembered UI state retires with its keyed slot.

## Known Limitations and Deferred Work

The default parser preserves the existing lightweight Markdown subset. This is not a full
CommonMark parser; replacements may provide a different grammar through the same contract.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- feature.markdown

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
