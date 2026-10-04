# Native product bundle

`nativePluginBundle(NativePluginServices)` returns the default Android/desktop core plugin composition. It contains no runtime lifecycle implementation. Model loop, storage, interaction, domain tools, coordinator and UI remain ordinary replaceable plugin mounts.

The runtime's default convenience entry uses this bundle. `KcodePluginRuntimeConfig.bundle` supplies an alternative list, and `KcodePluginProfile` controls inclusion, disabling and overrides. Only inventory and loader remain runtime bootstrap. Native factories append platform capability Providers and Consumers independently.

This module is the composition layer allowed to depend on concrete core Providers and Consumers. Feature Consumers depend on API definitions instead of importing the bundle or runtime. Native startup restores external installations and enable state through the optional installation store. Downloadable default packages remain deferred; the shipped default bundle is compiled into the host. Profile/headless composition tests cover these boundaries.
