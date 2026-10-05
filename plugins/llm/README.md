# Independent model providers

Each child module is one independently compiled and packaged model provider. The
parent directory has no aggregate Gradle project, vendor dispatcher or model inventory.
`adapter-support` is a private build library with registration mechanics only.
Generic registry, configuration, validation and standard UI belong to `llm-core`.
SDK contracts belong to `plugins/api`; providers do not depend on default UI.

Provider package IDs and entry classes remain unchanged. Their own model catalog
registration drives both settings and conversation selectors. Disabling a provider
removes its options and revokes calls while preserving saved identities and credentials.
Bedrock is packaged only for desktop. All other providers have desktop/Android variants.

## Adding a provider

Implement a Cordis plugin that injects `KcodeLlm.Key` and collects the disposer returned
by `register(ModelAdapter(...))` in its effect. Give the adapter and provider stable IDs;
declare its models, localized labels, connection requirements/defaults and optional
`iconId` / `regionChoices` in `ModelProviderSpec`. Create clients only in its owned factory.
The standard settings and conversation UI consume the committed catalog automatically.
No changes to a vendor switch or UI inventory are necessary. External provider IDs are
accepted without adding them to the built-in identity list.

Keep vendor client dependencies in the provider's own Gradle module. Include a new module
and its `BundledProvider` entry only when adding it to the default native distribution;
externally installed providers use the same SDK registration contract. Custom connection
forms can optionally register feature-owned default UI contributions.

## Common management

`llm-core` owns the registry, client lifecycle, settings policy and standard settings form.
Its implementation has no vendor client dependencies. The SDK exposes the abstract
service and contribution metadata; `adapter-support` shares private registration code
inside each vendor package. Provider withdrawal removes options and cancels owned work;
registry withdrawal also closes every outstanding client and rejects stale handles.
