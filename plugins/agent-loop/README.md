# agent-loop

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.agent-loop.koog

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.


The owned agent service captures the root-context `KcodeExecution` boundary when supplied by a managed runtime. Direct plugin calls participate in the same admission checks as native facade calls. Reply/streaming work and structured continuations/subagents remain owned until cleanup finishes.
