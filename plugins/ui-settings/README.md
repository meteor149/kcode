# Settings feature plugins

This module owns language, model connection, search and Shell settings forms, drafts,
validation, and save orchestration. Each contributes a separate `SettingsSection`
through `KcodeUiSlots`; the generic settings host owns only routing and layout.
Disabling or replacing a section withdraws its metadata and renderer together.
The native bundle selects these mounts explicitly, independently of the settings page.

Forms share stateless settings groups and credential fields from `ui/component`,
with the application's theme, design tokens and localized text. Feature-specific
rows and provider decisions live here. Model requirements and defaults come from
the committed provider catalog passed in `SettingsPageRequest`; when the catalog
is empty, the model settings section is hidden. A test withdraws all model adapters,
re-enables one, and independently disables and re-enables the section.
