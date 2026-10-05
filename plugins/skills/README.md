# Skills provider

`WorkspaceSkillsPlugin` is the native default entry, with Unit configuration and a required
`KcodeSkillWorkspace` service. Each activation creates its own catalog, builtin materialization
and discovery state. It publishes `KcodeSkills` through `SkillServicePlugin`, whose operation
owner cancels and joins calls on withdrawal and rejects retained runtime handles.

Workspace loss makes the provider Pending. Recovery creates fresh caches while retaining user
files and materializing the builtin Web application skill through the selected workspace.
Custom in-process compositions can use `SkillServicePlugin` with `SkillServicePluginConfig`
to supply an explicitly borrowed runtime instead of the native workspace implementation.

Native distributions ship `provider.skills.platform` as an independent dual-target `.kplugin`.
Its private implementation uses only shared SDK contracts and is absent from production host
classpaths. Desktop package tests cover catalog behavior, workspace withdrawal, stale handles
and restoration; Android package tests verify actual APK loading and builtin catalog entries.
