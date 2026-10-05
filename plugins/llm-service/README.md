# LLM service registry

`LlmServicePlugin` is the Unit-configured entry point for `core.llm`. It creates the SDK
`KcodeLlm` registry during Cordis apply; model adapters inject this service and own their
registrations. The registry contract and adapter lifetime wrappers remain in `plugins/api`.

Native distributions ship this provider as an independent `.kplugin` with desktop JAR and
Android APK variants. Host production classpaths exclude this module. Disabling `core.llm`
withdraws adapter registrations, suspends dependent consumers, and revokes retained adapter
handles. Re-enabling creates a new registry and rebinds providers without changing settings
or saved credentials. Production package fixtures cover both platforms and this lifecycle.

The provider-specific clients, catalogs and generated model-label dictionaries remain in
`plugins/llm`; they ship as independently loaded model-adapter packages. This module introduces
no model-client dependencies or public SDK ABI changes. In-process compositions that mount
`LlmServicePlugin` directly must include this module on their runtime classpath.
