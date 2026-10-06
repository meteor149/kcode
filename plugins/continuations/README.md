# continuations

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- core.continuations

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.
