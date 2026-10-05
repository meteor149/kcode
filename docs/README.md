# kcode documentation

This directory maintains current usage guides, architectural boundaries, and verification methods.
For the project overview and installation, see the [English README](../README.md)
and [Chinese README](../README.zh-CN.md).

## Architecture and development

| Task | Guide |
| --- | --- |
| Understand the boundaries of the SDK, providers, consumers, platform hosts, and default UI | [Plugin architecture](plugin-architecture.md) |
| Develop plugins, manage configuration and resource lifecycles, and package external JARs/APKs | [Plugin development](plugin-development.md) |
| Build/import cross-platform archives, select variants and commit package dependency sets | [Plugin package format](plugin-package-format.md) |
| Locate a feature's implementation module and test entry points | [Feature ownership](plugin-feature-audit.md) |
| Build, verify revocation/replacement, and assess the scope of test evidence | [Verification guide](verification.md) |
| Develop the default UI using design contracts and shared components | [UI design system](ui-design-system.md) |
| Distinguish app-UID, UID-2000, and Shizuku/root verification | [Privileged verification](verification.md#privileged-verification) |

## Usage and platform capabilities

| Task | Guide |
| --- | --- |
| Configure model and search credentials through ADB and diagnose broadcast failures | [ADB settings](adb-settings.md) |
| Save and read Web Artifacts | [Artifact storage](artifacts.md) |
| Understand Android Ubuntu identity, workspaces, and installation | [Android Ubuntu](android-ubuntu-runtime.md) |
| Debug Web containers and native capability bridges | [Web container guide](../plugins/web-container/WEB_CONTAINER_GUIDE.md) |

## Design references

[Harness plugin design](deepseek-harness-plugin-spec.md) describes the reference architecture
at the stated commit. [Harness reserved APIs](harness-reserved-api.md) describes contracts
for which kcode does not yet provide implementations. Neither document implies that the
current application implements those capabilities.

## Documentation maintenance

- Architecture documents describe current boundaries; module READMEs describe entry points, configuration, and limitations.
- Keep operational steps in topic guides. The two project READMEs retain overviews and links.
- Source code is authoritative for public signatures, versions, and the default plugin list. The current API version is defined in [AgentPluginManager.kt](../plugins/api/src/commonMain/kotlin/ai/meteor/kcode/plugin/AgentPluginManager.kt).
- Record verification methods separately from individual results. Old test counts, device state, and log filenames do not replace verification of a new version.
- When moving or deleting documents, update this index, both project READMEs, and module README references.
- Write documentation in this directory in English.

Use current source code to determine module boundaries. Keep historical versions, migration
details, and one-time verification records out of usage instructions.

Feature-owned settings and command contribution boundaries are described in [plugin architecture](plugin-architecture.md#feature-owned-settings-api-45); current validation is recorded in [verification](verification.md#feature-owned-settings-api-45-2026-10-05).
