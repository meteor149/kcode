# Shell tool consumers

Shell consumers borrow execution services; native process, privilege and Ubuntu deployment
policy remains with `plugins/native-execution`. Their effects own tool registrations.

Native distribution offers three independently enabled packages:

| Package | Platform | Entry in `ai.meteor.kcode.plugin.feature` | Config |
| --- | --- | --- | --- |
| `consumer.tools.shell` | Desktop | `DesktopShellToolConsumerPlugin` | `Unit` |
| `consumer.tools.android-shell` | Android | `DefaultAndroidShellToolConsumerPlugin` | `Unit` |
| `consumer.tools.ubuntu-shell` | Android | `DefaultUbuntuShellToolConsumerPlugin` | `Unit` |

The default Android entries reuse the existing platform-specific tool descriptions.
`AndroidShellToolConsumerPlugin` and `UbuntuShellToolConsumerPlugin` remain available
for custom compositions with a nonblank `String` description validated at activation.
Consumers require `KcodeTools` and `KcodeShell` / `KcodeUbuntuShell`; provider removal
suspends them until services return. Platform hosts offer these packages with or without
the default product composition and skip unsupported archives before opening resources.

Production-classpath JAR and real APK tests prove host class absence, target selection and
registration withdrawal/recovery. The desktop test decodes private tool arguments through
the shared serializer, invokes a harmless echo command through the real native executor,
rejects a retained tool after disable and executes through the restored consumer. Android
activation checks do not establish Shizuku/root permission or execute an Ubuntu command.
