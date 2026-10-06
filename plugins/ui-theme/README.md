# Default theme

`DefaultThemeUiPlugin` publishes `provider.ui.theme` through the optional default UI registry.
Its private renderer supplies `KcodeTheme` from `libraries:ui`, with deployable colors, tokens
and font scaling. Validation occurs before replacing the committed provider. Unknown keys,
invalid types, nonfinite/out-of-range values and inconsistent bubble dimensions are rejected.
Disabling the theme leaves its slot empty. Configuration and the package ID are unchanged.

Example configuration:

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


Verify with `gradlew.bat :plugins:ui-theme:desktopTest` and platform private rendering tests.

## Profile export policy

The release-owned schemas under "src/profile-export" declare portable configuration for:

- provider.ui.theme

Explicit Unit configuration is portable. Other codecs and undeclared fields are rejected
unless the corresponding schema explicitly permits them. Machine bindings and persisted
credentials are outside these configuration declarations.

The verified release also permits JSON theme configuration with explicit color/extended-color
roles, spacing, radius, size, glass, overlay and fontScale fields. Colors use the bounded
hex-color string format, accepting only six-digit RGB or eight-digit ARGB. Numeric bounds
match the deployment configuration ranges; arbitrary text, unknown roles/properties and
out-of-range values are denied, including values hidden beneath later overrides. ConfigValidator
still checks relationships such as bubble minimum/maximum widths before allocation. Policies
are selected from the locked release; a newer schema does not review an older generation.
