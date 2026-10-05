# Bedrock model provider

`provider.llm.koog.Bedrock` exports `BedrockModelAdapterPlugin` with Unit configuration.
It injects KcodeLlm and registers an owned SDK adapter with its model catalog.
Withdrawal revokes factories, cancels requests and removes settings/conversation options.
Saved identities and credentials remain persisted.

Client dependencies, connection policy, models and private labels belong to this module.
Default UI reads the committed catalog; this provider requires no UI services.

Bedrock is packaged only for desktop; Android does not advertise its catalog.

Verify with `gradlew.bat :plugins:llm:bedrock:desktopTest` and private package loading tests.
