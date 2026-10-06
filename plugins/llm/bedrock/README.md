# Bedrock model provider

`provider.llm.koog.Bedrock` exports `BedrockModelAdapterPlugin` with Unit configuration.
It injects KcodeLlm and registers an owned SDK adapter with its model catalog.
Withdrawal revokes factories, cancels requests and removes settings/conversation options.
Saved identities and credentials remain persisted.

Client dependencies, connection policy, models and private labels belong to this module.
Default UI reads the committed catalog; this provider requires no UI services.

Bedrock is packaged only for desktop; Android does not advertise its catalog.

Verify with `gradlew.bat :plugins:llm:bedrock:desktopTest` and private package loading tests.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.llm.koog.Bedrock

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
