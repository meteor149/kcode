# Desktop platform and Profile startup

This module supplies verified JAR loading, native package preparation and SDK host adapters.
`createDesktopKoogChatRuntime` accepts `profileId` and `homeDirectory` (default `.kcode`).
An explicit Profile ID takes precedence over saved selection and the `native` template.
The desktop application accepts `--profile <id>` or `--profile=<id>`.

The factory prepares bundles, package locks and machine configuration before product
allocation. Profile metadata lives in `<homeDirectory>/profiles`, verified deployments in
`<homeDirectory>/plugins`. Settings/history use logical data scopes; workspace configuration
uses the legacy workspace or a directory under `<homeDirectory>/workspaces`.
Caller stores are borrowed and keep their caller-defined scope and lifetime.

Committed bundle snapshots and release locks govern restart. Edited drafts do not replace
successful generations. `AgentPluginManager` updates active Profile intent through native
module/tree transactions, including verified archive import and independent instance removal.
`createDesktopProfileHost` constructs a stable `KcodeProfileHost`; the existing runtime factory
returns its chat/manager/application facades with the host as owner. `switchTo` stages the target,
closes the previous product, publishes generation/selection together and changes the delegates.
Failed target activation restores the old locked generation. Active work requires explicit
cancellation/join, and failed closure/restoration closes execution admission. Each allocation
receives fresh host inputs; the retained catalogue contains IDs rather than old mounts/resources.
The Plugin API 66 manager exposes catalogue/draft reads, revision-checked draft writes,
cloning, deletion, preview, history and activation of committed/draft/historical intent.
Preview prepares the deployment without mounting providers. Historical activation appends a
new generation; clones retain code intent and use separate business data scopes by default.
Management UI and recovery UI remain pending; see the Profile guide.
