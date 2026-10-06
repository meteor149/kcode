# OpenRouter model provider

`provider.llm.koog.OpenRouter` exports `OpenRouterModelAdapterPlugin` with Unit configuration.
It injects KcodeLlm and registers an owned SDK adapter with its model catalog.
Withdrawal revokes factories, cancels requests and removes settings/conversation options.
Saved identities and credentials remain persisted.

Client dependencies, connection policy, models and private labels belong to this module.
Default UI reads the committed catalog; this provider requires no UI services.

The native distribution publishes independent desktop JAR and Android APK variants.

Verify with `gradlew.bat :plugins:llm:openrouter:desktopTest` and private package loading tests.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.llm.koog.OpenRouter

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
