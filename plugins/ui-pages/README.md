# Typed UI contributions

`default-ui-bridge` supplies `core.ui-slots` / `KcodeUiSlots` over the independently owned neutral registry in `ui-contributions`. This module contains concrete default consumers only. `provider.ui.chat`, `provider.ui.artifacts`, `provider.ui.settings`, `provider.ui.conversation.standalone` and `provider.ui.theme` register typed renderers using `ApplicationSlots` and collect their Disposables. Each slot permits exactly one contributor; duplicate registration fails rather than silently replacing an owner.

UI consumes immutable committed `ApplicationUiSlots` snapshots. Page renderers receive typed state, service contracts, and callbacks, never a platform implementation or mutable Cordis context. Default renderers and chat page implementations live in this module; Artifact page, launcher and navigation live in `artifact-tools`. Shared provides generic controls, resource access, typed contracts and a parameterized KcodeTheme entry. Default message, selection, tool-card, transcript and theme implementations are private to this module. System overlays consume the independently revocable conversation.transcript slot instead of depending on concrete message UI. Replacing a page keys that page's composition; unrelated contributions preserve the application session's remembered state.

Settings sections and forms belong to their feature packages (`localization`, `model-settings`, `web-search`, and `execution-settings`). Providers mount their settings contributions as child Fibers and register them through the optional default UI SDK. The page consumes only registered, visible sections and generic persistence callbacks; it imports no feature providers. Unloading the open section returns to the root. Disabling one feature withdraws only its settings item while the page and other items remain registered.

Messages use `messagePresentationPlugin` and tool cards use `toolUsePresentationPlugin`. The first matching `supports` contribution in order/id order renders the content; user, assistant, error and default tool card are separate plugins. Custom renderers can precede the default with a smaller order. Message rendering, selection and scroll implementations live in this module’s private `pages/chat/component`; chat layout, composer, model selection, welcome content and export presentation live here.

Navigation uses `navigationPlugin` and `registerNavigation`. Each contribution owns an opaque route id, ordering, localized title, icon, availability predicate, renderer, and optional conversation role. The shell renders these routes without a fixed destination enum. The default chat route belongs here; Artifact navigation belongs to `feature.artifacts` and withdraws with that feature. Routes hide when their page slot disappears. Removing the selected route temporarily renders the first available route while retaining the selected identity for recovery; unrelated contributions preserve the selected route. New/select conversation actions use the first available contribution declaring the conversation role.

Missing slots stay empty. Disabling the theme pauses default application rendering.
Conversation features prepare generic header, header-action and above-composer projections.
Export menus, state, result notices and labels belong to `conversation-export`; this page
does not receive an exporter or invoke Save/Share. Message selection remains generic, and
the compact New Chat action stays available when export is disabled. Compose recomposition,
replacement, disable/re-enable and duplicate-registration rollback are covered by
`PluginCompositionTest`; no model tools are contributed here.

The standalone conversation overlay is an independently removable slot. Its consumer
depends on `sessions`, while the feature-owned Artifact page depends on `artifacts`; missing providers
withdraw the corresponding views. The host invokes committed renderers and never calls
a default overlay implementation directly. Tests verify view withdrawal, dependency
Pending states, reactivation, and preservation of the unrelated chat slot. Artifact
launch mapping and its test live with the page, and page cancellation is propagated.
The shared glass-effect state is opaque so page plugins do not receive its third-party
implementation type through the public component API.

WebContainer's overlay, actions and lifecycle tests belong to `plugins/web-container`.
This module's default page list no longer mounts that feature contribution. MainPage
consumes its optional typed renderer; it imports no WebContainer UI implementation.

`provider.ui.layout` and `provider.ui.sidebar` own the responsive layout and sidebar.
The shell supplies an `ApplicationLayoutRequest` with a composable content callback
and `SidebarPageRequest` with committed navigation/session projections and actions.
Shared hosts key each renderer independently. Disabling the sidebar preserves layout
and content; disabling the layout disposes its child compositions. No hidden default
renderer replaces a missing slot. Desktop Compose tests cover cleanup/re-enable and
preserved application state. An isolated APK renders both slots, receives host data,
and invokes host navigation and content callbacks before disable/re-enable/uninstall.


`provider.ui.theme` owns the default palette, typography and all shared design-token defaults in
private `pages/ui/design`. Shared color/metric accessors read the currently supplied theme. The
single SDK `KcodeTheme` entry requires explicit colors, shapes, typography and design tokens.
Generic widgets have no default kcode palette or metrics outside this theme context.

The theme entry accepts `Unit` for builtin defaults or a JSON object in a deployment config:

```json
{
  "colors": {"surface": "#223344", "onSurface": "#DDEEFF"},
  "extendedColors": {"panel": "#334455", "selectedSurface": "#445566"},
  "spacing": {"md": 22},
  "radius": {"control": 19},
  "size": {"touchTarget": 64},
  "glass": {"blurRadius": 6, "tintOpacity": 0.5},
  "overlay": {"floatingSize": 68},
  "fontScale": 1.25
}
```

Colors support six-digit RGB or eight-digit ARGB strings. Unknown keys, invalid types,
nonfinite/out-of-range values, and a bubble minimum wider than its maximum are rejected before
the committed theme is withdrawn. Font scaling preserves system accessibility scaling. Disabling
the theme leaves the application slot empty; enabling an installed package restores its configuration.

The settings sheet reuses BottomSheetOverlay from `libraries:ui`.
Its Android and desktop dialog properties live in that independent library, along with
basic controls, design contracts and icons. Page orchestration, chat state bindings and
the product theme remain private to this plugin. JAR/APK tests verify the shared
library identity across private renderers and absence of the old private sheet facade.

Subagent status/detail presentation belongs to `subagents`. The page prepares generic
conversation contributions once, renders `Header` contributions above content and
`AboveComposer` contributions above the input area, and measures the latter to reserve
compact-layout scrolling space. It passes shared haze state through the request instead
of importing or filtering Subagent state.

The sidebar consumes prepared `SidebarPageRequest` values and does not require
the Sessions service. Missing session capabilities produce an empty conversation
projection and unavailable conversation actions, while settings/navigation remain.

Default page providers own compiled English texts from `src/main/ui-texts`. Each
provider registers its defaults independently, so withdrawing a layout does not
remove texts owned by surviving pages. Core pages and message presentation no longer
inject Localization; the root supplies the prepared rendering catalog.
