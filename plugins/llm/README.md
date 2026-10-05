# LLM provider plugins

`core.llm` owns the adapter registry through the independent [llm-service](../llm-service/README.md) package. Each native provider is a separate mount,
`provider.llm.koog.<ModelProvider.name>`, rather than one aggregate registration.
Client implementations, vendor SDK dependencies, model inventories, requirements,
and connection defaults live in this module. Shared code contains vocabulary and
catalog snapshot contracts.

An adapter may contribute a `ModelProviderSpec`. Its catalog and runtime factory
share one disposable registration. Duplicate provider owners, invalid model
metadata, and duplicate model identifiers fail before publication. Withdrawal
invalidates previously resolved factories. The adapter owns all returned clients
and their requests; withdrawal cancels and waits for request cleanup before closing
clients. Agent executions also close their client on completion, failure, or
cancellation; repeated closes are idempotent.

Runtime composition commits publish the catalog to the application renderer and
the host's catalog query. Settings and chat selectors use that snapshot. A missing
provider or model yields an incomplete configuration instead of silently choosing
another model. Android does not advertise Bedrock, whose client is unavailable on
that platform. The previous aggregate enable state is migrated into individual
mount states when restoring installation manifests.

External APK tests replace DeepSeek's catalog through host-owned ABI types and
verify disable, enable, and uninstall. Desktop composition tests cover duplicate
provider replacement rollback, stable Compose state, and legacy enable migration.
SDK catalog, connection, serialization, and client construction tests live here.

`ModelProvider(id)` accepts opaque provider routes. Historical built-in ids remain
unchanged in persistence; their `entries` list only supports native bundle assembly
and legacy aliases. External adapters contribute display names, descriptions,
models, and supported connection metadata through `ModelProviderSpec`, without
editing the host. Settings stores retain custom route keys when the plugin is
temporarily absent. Compose, ADB, real DataStore/MMKV, and isolated APK tests cover
registration, withdrawal, and restoration. Dynamic plugins use the current SDK API version 47 and
must be recompiled for the changed provider and catalog ABI.

Each of the 11 built-in routes now has a named `Plugin<Unit>` export in
`plugin.llm.NativeModelAdapterPlugins`: for example `DeepSeekModelAdapterPlugin`.
The native bundle mounts these entries. Every mount constructs its own catalog,
support predicate, and client factory inside the plugin; it does not capture an
adapter constructed by the host. `modelAdapterPlugin` remains the borrowed custom
adapter composition helper. Actual JAR and APK tests load all formal entries,
reject invalid configuration before withdrawal, revoke stale adapters, and restore
the catalog after enabling. DeepSeek's private factory is exercised up to a test
HTTP allocation boundary without sending network requests. Android's Bedrock
entry registers no selectable catalog or supported runtime.


The adapter contributes provider/model display text in the committed catalog. The English and
Chinese dictionaries in `src/main/localization` compile into private package bytecode through
`generateModelLabels`; external JAR/APK loading never looks up host copies of these labels.
Shared presentation helpers project the current catalog and do not restore built-in labels after
withdrawal. Alibaba endpoint selection also belongs to this module; the SDK region code keeps
its persisted identity without carrying endpoint policy.

Native distributions now ship each model adapter as an independent package. Ten providers
offer desktop JAR and Android APK variants; Bedrock is desktop-only. Native hosts exclude
this module from their production classpaths. Koog framework contracts preserve host identity; vendor clients, adapter factories,
generated dictionaries and catalogs stay private to each package. Production fixtures exercise every supported adapter's client allocation boundary
without model requests, withdrawal/recovery, and actual desktop Bedrock construction.

The fallback `defaultModelAdapterPlugins` composition helper lives in `bundle-native`.
It resolves an entry only when that ID has no package owner, and Android omits Bedrock.
In-process tests still supply this module explicitly on their runtime classpaths.

API 47 gives each shipped adapter its own desktop JAR and Android APK dependency closure.
The shared SDK contains Koog framework contracts; vendor clients, the OpenAI-compatible
base, and desktop AWS/Smithy types load privately from the owning archive. Providers using
an OpenAI-compatible endpoint share that implementation as a build dependency, while
retaining independent deployment and lifecycle. Unrelated vendor client jars are omitted. Bedrock includes the Anthropic dependency used
by its upstream model definitions and wire serializers.
Cordis `prepareProviderLlmKoog<Provider>DesktopJar` tasks also supply the actual private-loading
fixtures. `prepareProviderLlmKoog<Provider>AndroidApk` builds APKs directly from the existing
Android library AAR. `packageProviderLlmKoog<Provider>` produces each logical release;
Bedrock remains desktop-only. The distribution catalog selects vendor component exclusions.
