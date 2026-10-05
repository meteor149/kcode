# Filesystem tool consumer

`consumer.tools.filesystem` uses `KcodeTools` and `KcodeFileSystem` to register the
Koog read, list, write, edit and media-read tools. It does not allocate a filesystem
backend. Withdrawal removes the contribution; backend withdrawal leaves it Pending
until a replacement is available.

Native builds distribute this consumer as a dual-target `.kplugin`. The entry is
`ai.meteor.kcode.plugin.feature.FilesystemToolConsumerPlugin` with `Unit` configuration.
The implementation and its adapters remain private to the package. Native hosts load
the package even when `profile.includeDefaults` is false, preserving platform feature
composition; service availability still controls activation.

Production-classpath JAR and real APK distribution tests prove host class absence and
contribution removal/re-registration. The desktop fixture also withdraws/restores the
packaged filesystem provider and checks dependency recovery.
