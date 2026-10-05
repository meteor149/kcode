# Skills provider

`WorkspaceSkillsPlugin` is the native default entry, with Unit configuration and a required
`KcodeSkillWorkspace` service. Each activation creates its own catalog and discovery state. It publishes `KcodeSkills` through `SkillServicePlugin`, whose operation
owner cancels and joins calls on withdrawal and rejects retained runtime handles.

Workspace loss makes the provider Pending. Recovery creates fresh caches while retaining user
files in the selected workspace.
Custom in-process compositions can use `SkillServicePlugin` with `SkillServicePluginConfig`
to supply an explicitly borrowed runtime instead of the native workspace implementation.

Native distributions ship `provider.skills.platform` as an independent dual-target `.kplugin`.
Its private implementation uses only shared SDK contracts and is absent from production host
classpaths. Desktop package tests cover catalog behavior, workspace withdrawal, stale handles
and restoration; Android package tests verify actual APK loading and user skill catalog entries.

## Skill tool consumer

`consumer.tools.skill` consumes `KcodeTools` and `KcodeSkills`, registering skill tools
when a skill runtime is supplied. It borrows the runtime and removes its contribution
on withdrawal. Missing services suspend the consumer until replacements return.

Native builds distribute a dual-target `.kplugin` with
`ai.meteor.kcode.plugin.feature.SkillToolConsumerPlugin` and `Unit` configuration.
The consumer implementation stays outside the host runtime dependencies. Platform hosts
offer this package with or without the default product composition.

Production-classpath JAR and real APK tests check host class absence, contribution
withdrawal and exactly one contribution on recovery. The desktop fixture also removes
the filesystem, causing the skills provider and this consumer to become Pending.


Provider and tool consumer share this source module while retaining their existing
package IDs, entry points and independent enable states. Tool consumers inject SDK
services; disabling a tool consumer does not close the provider or its workspace.
