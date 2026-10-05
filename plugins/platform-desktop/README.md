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
Runtime Profile switching and the management UI remain pending; see the Profile guide.
