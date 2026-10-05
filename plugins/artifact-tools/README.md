# Artifact feature

`feature.artifacts` owns Artifact tools, the default Artifact page and its navigation
contribution in one dual-target install/enable boundary. The entry is
`ai.meteor.kcode.plugin.ArtifactFeaturePlugin` with `Unit` configuration. Implementation
lives here, including `pages/artifact` and its launcher tests; generic `ui-pages` contains
no Artifact page, navigation renderer or launcher implementation.

Internal children declare their own required services. Tools consume `KcodeTools` and
`KcodeArtifacts`; page/navigation additionally require `KcodeUiSlots` and localization.
Without default UI or localization, tools remain usable. Without an Artifact repository,
children withdraw their contributions while the feature remains mounted. The storage
backend remains independently replaceable and is borrowed through SDK contracts.

The page owns its list and Web launch operations. Withdrawal cancels and joins held
cleanup and rejects retained page actions, without closing borrowed repositories or Web
controllers. Feature withdrawal removes tools, page and navigation together; unrelated
chat/settings contributions remain available. Re-enabling registers one fresh generation.

The old `consumer.tools.artifact`, `provider.ui.artifacts` and
`provider.ui.navigation.artifacts` IDs migrate to the aggregate. Legacy entry classes and
tool contribution IDs remain for external dependency graphs and explicit alternative
compositions; they are not three default releases.

`ArtifactLauncherTest` verifies Web launch mapping, held page cleanup and stale action
rejection. Formal private-loading tests import actual JARs/APKs and verify shared SDK and
private renderer identity, withdrawal, replacement rollback and recovery. Package migration
tests preserve old external dependencies and disabled choices. Android test-source
compilation alone does not establish device execution.
