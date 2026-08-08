# kcode UI design system

This document is the sizing contract for shared Compose UI on Android, iOS,
desktop, and web. New components should consume the tokens in
`app.kcode.ui.KcodeDesignSystem` instead of introducing one-off values.

## Principles

- Use a restrained, content-first hierarchy: white canvas, neutral surfaces, and
  green only for primary actions, focus, progress, and selection.
- Use `sp` typography through `MaterialTheme.typography`; never clamp the user's
  platform font scale.
- Use the 4dp micro-grid and the 8dp primary rhythm. Exceptions are limited to
  strokes, optical icon alignment, platform insets, and drawing geometry.
- Keep visual controls compact, but preserve a 48dp minimum touch target on touch
  platforms. Pointer-first desktop controls may use the 40dp compact size.
- Prefer responsive width constraints and wrapping over device-specific sizes.

## Typography

| Role | Token | Size / line height | Typical use |
| --- | --- | --- | --- |
| Display | `displaySmall` | 28 / 36sp | Empty-state quote, rare hero copy |
| Page heading | `headlineSmall` | 22 / 28sp | Page and modal titles |
| Section heading | `titleLarge` | 18 / 24sp | Major section heading |
| Component heading | `titleMedium` | 16 / 24sp | Card and popup heading |
| Compact heading | `titleSmall` | 14 / 20sp | Setting row and tool title |
| Reading body | `bodyLarge` | 16 / 24sp | Chat and long-form content |
| UI body | `bodyMedium` | 14 / 20sp | Popup choices and desktop UI |
| Supporting body | `bodySmall` | 12 / 16sp | Secondary descriptions |
| Action label | `labelLarge` | 14 / 20sp | Buttons and navigation |
| Section label | `labelMedium` | 12 / 16sp | Group labels and metadata |
| Micro label | `labelSmall` | 11 / 16sp | Short hints and compact status |

Do not use the micro label for paragraphs. Do not create hierarchy only by
changing size: weight, color, and whitespace should support it.

## Spacing and sizing

| Token | Value | Use |
| --- | ---: | --- |
| `hair` | 4dp | Icon/text optical gap, tight metadata |
| `xs` | 8dp | Related items, compact row vertical padding |
| `sm` | 12dp | Standard component padding |
| `md` | 16dp | Card/panel horizontal padding |
| `lg` | 24dp | Section separation |
| `xl` | 32dp | Major content separation |
| `xxl` | 40dp | Sparse/hero separation |
| `touchTarget` | 48dp | Minimum touch target |
| `compactControl` | 40dp | Pointer-first desktop control |

Use `control` (12dp), `card` (20dp), and `panel` (28dp) corner radii. A child
surface should not have a larger radius than its parent.

## Component density

- Bottom sheet: on compact layouts, anchor to the bottom edge at 99% viewport
  height with 34dp top corners and no side gutter; on wider layouts, constrain
  content to 680dp and 90% height. Use an 18% black scrim and keep system status
  UI visible behind it.
- Anchored popup: 216–280dp where content permits; 14sp option text, 11–12sp
  supporting text, 8dp row padding, and 12–16dp horizontal padding.
- Settings row: at least 48dp effective height, 14sp title, 11–12sp description.
- Secondary settings pages omit copy that merely repeats the page title or
  explains persistence internals. Keep descriptions only when they distinguish
  choices or communicate a security consequence, and pair selectable rows with
  a 32dp semantic line icon.
- Composer: 16sp mobile input and 14sp desktop input. The dense composer is the
  documented exception to the general 48dp target rule and uses fixed 40dp-high
  controls: file/send 40dp, permission 80dp, and model 136dp on narrow layouts
  or 144dp otherwise. Only the empty gap between controls may expand.
- Chat content: 16/24sp on touch layouts and 14/20sp on wider desktop layouts.
- Dividers: 0.5–1dp and inset to the text column rather than spanning icons.

## Motion

- Bottom sheets enter from the bottom over 320ms and leave over 240ms with the
  scrim fading independently. Back, close, and scrim taps share the same exit
  path so the sheet is never removed before its animation completes.
- Every clickable surface uses the shared `pressScale` interaction instead of a
  component-specific animation.
- Compact, circular, and primary buttons scale to 93% while pressed.
- Rows, cards, popup choices, and other content panels scale to 97% so their
  text remains visually stable.
- Press-in uses a 110ms ease; release uses a softer spring (`dampingRatio 0.68`,
  `stiffness 380`). Scaling is applied through a graphics layer, so layout and
  neighbouring controls do not move.
- Disabled controls remain at 100%, and draggable or dismiss-only scrims do not
  scale.
- Conversation changes keep the surrounding chrome stable while the new content
  fades and travels 16dp from the trailing edge over 280ms. The transition must
  not create a second chat scope or cancel background streaming work.

## Review checklist

1. Every new text element maps to a typography role.
2. Spacing comes from `KcodeSpacing`; new spacing values require a documented
   optical or platform reason.
3. Touch actions expose at least a 48dp hit area.
4. Content remains usable with platform font scaling and narrow screens.
5. Theme green communicates state or action rather than decorating neutral UI.

## References

- Material 3 typography scale: https://developer.android.com/develop/ui/compose/designsystems/material3
- Android accessibility touch targets: https://developer.android.com/guide/topics/ui/accessibility/apps
- Material layout rhythm: https://m1.material.io/layout/metrics-keylines.html
