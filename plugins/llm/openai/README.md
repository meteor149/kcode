# OpenAI model provider

`provider.llm.koog.OpenAI` exports `OpenAIModelAdapterPlugin` with Unit configuration.
It injects KcodeLlm and registers an owned SDK adapter with its model catalog.
Withdrawal revokes factories, cancels requests and removes settings/conversation options.
Saved identities and credentials remain persisted.

Client dependencies, connection policy, models and private labels belong to this module.
Default UI reads the committed catalog; this provider requires no UI services.

The native distribution publishes independent desktop JAR and Android APK variants.

Verify with `gradlew.bat :plugins:llm:openai:desktopTest` and private package loading tests.
