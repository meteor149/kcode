# Markdown provider and UI contribution

`MarkdownProviderPlugin` supplies the neutral `KcodeMarkdown` / `MarkdownContent` contract.
Block parsing, inline styles, plain-text projection, palette and Compose rendering live in
this module's private namespace. `MarkdownUiContributionPlugin` independently binds the
active provider to the committed `content.markdown` slot; it never constructs a fallback.

Messages consume the slot, image export and scheduled notifications inject the service.
Provider withdrawal removes the slot and suspends the latter consumers. Retained formatting
references reject new calls; retiring UI frames render nothing. Formatting computations are
pure and own no IO, threads or coroutines. Remembered UI state retires with its keyed slot.

## Known Limitations and Deferred Work

The default parser preserves the existing lightweight Markdown subset. This is not a full
CommonMark parser; replacements may provide a different grammar through the same contract.
