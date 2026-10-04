# Reusable UI library

`:libraries:ui` is an independent Kotlin Multiplatform library for Android and desktop.
It depends on Compose, coroutines and Haze, with no dependency on `shared`, Cordis,
plugin APIs, localization, repositories, or feature implementations.

It owns reusable inputs, settings groups, popup rows, bottom sheets, pointer/press helpers,
glass effects, icons and vector resources. `ui/design` defines the parameterized KcodeTheme
entry and design contracts. Product palettes, typography values and default design settings
remain owned by the theme plugin. Components accept data, localized labels, slots and
callbacks; they do not query application services or navigate.

UI plugins explicitly depend on this library. `plugins:default-ui-api` exports it because
its default UI contracts include library types. A root UI using only `plugins:api` can
choose an entirely different component library and theme. This library never registers
a root, page, sidebar, navigation destination or Cordis service.

The native host shares `ai.meteor.kcode.ui.component`, `ai.meteor.kcode.ui.design` and
`ai.meteor.kcode.ui.resources` across plugin ClassLoaders. This preserves type and resource
identity when multiple private plugin renderers reuse the same library. Host sharing does
not force UI plugins to render these components. Component effects and dialogs belong to
the calling plugin's Compose subtree and retire when that subtree is withdrawn.

API 34 changes resource accessors and requires localized strings for ApiKeyField;
external plugin packages must be rebuilt. BottomSheetOverlay and its platform dialog
properties are now library code, replacing the API 33 private ui-pages helpers. Real JAR/APK
contribution tests check shared library identity, private renderer identity and absence of
the former private BottomSheet facade.
