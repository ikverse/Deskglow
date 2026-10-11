# Deskglow guidelines

The rules any change must follow. They describe the app as it is (see
[AUDIT.md](AUDIT.md) for what was measured and found); where a rule is not yet met everywhere,
the matching ticket in [TASKS.md](TASKS.md) brings the code up to it. Targeted fixes inside this
design, never a new visual direction.

Paths are relative to `android/app/src/main/java/com/ikverse/deskglow/`.

---

## 1. Design rules

### Colour
The app's chrome and the display are two palettes on purpose: the chrome is warm, the display
is pure black and white so it reads as a device, not an app.

**App chrome: use `Palette` in `ui/Theme.kt` and nothing else.**

| Token | Value | Use |
| --- | --- | --- |
| `Page` | #000000 | The window background. |
| `Sheet` | #0F0E0C | Cards, the editor sheet, dialogs. |
| `Raised` | #181614 | Buttons, menus, the action pill, the dock. |
| `Rule` | #26231F | 1 dp dividers and card outlines. |
| `Edge` / `EdgeStrong` | #2E2A25 / #4A453D | Tile and swatch outlines. |
| `Ink` | #EDE6DA | All body text and icons. |
| `Muted` | #8A8378 | Secondary text, inactive icons (5 : 1 on `Sheet`). |
| `Select` = `Accent` | #FFB547 | The one active thing: selection, current screen, the primary button, the lit tab. Nothing else is amber. |
| `OnAccent` | #1C1205 | Text on amber. |
| `Danger` | #FF6B5A | Delete, reset. |
| `Glow` | #55FFB547 | The halo on the primary button and the selected widget only. |
| `ToastFill` / `ToastEdge` | #232019 / #38342C | The undo toast. |
| `Bar` | #F00F0E0C | The editor's top bar. |

- Never write a hex literal in `ui/`. Add a token to `Palette` with a one-line comment instead.
- Material components take their colours from the scheme in `DeskglowTheme`. When a component
  shows a colour that is not in the table above (a violet slider track, a grey dialog), the fix is
  to map the missing Material role in `darkColorScheme(...)`, not to pass colours per call site.
  Roles that must be mapped: `primary`, `onPrimary`, `background`, `surface`, `surfaceVariant`,
  `onSurfaceVariant`, `outline`, `outlineVariant`, `error`, `secondaryContainer`,
  `onSecondaryContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`.
- Dialogs pass `containerColor = Palette.Sheet`; menus pass `containerColor = Palette.Raised`.
- Disabled is `Muted` at 35% alpha; a chevron is `Muted` at 70%.

**Widgets (the display): `widgets/WidgetType.kt` tokens only.**

- Text defaults to the widget's own colour setting (`Common.COLOUR`, white) and `Muted`
  (#8C8C8C) for secondary text.
- Tracks, rules and rails (the unlit part of a ring, a progress bar's bed, the thin vertical rule
  in the weather widget, the hairline under a clock) use one shared set of tokens:
  `WidgetPalette.Track` (#262626), `WidgetPalette.Rail` (#2E2E2E), `WidgetPalette.Rule`
  (#333333). Do not introduce another near-black.
- Accent colours are user settings (`ACCENT` keys) with these defaults: green #44B98A (battery),
  amber #F5B942 (sun, alarm), teal #5BC0A6 (prayer), F1 red #E10600. Flags and tyre compounds use
  the real-world colours in `F1LiveLayouts.kt`; team colours come from the feed or `teamColour()`.
- Anything drawn behind text on the display is black, so overlapping shapes cut a black gap
  first (see `WeatherArt.kt`) rather than using shading.

### Type
`Type` in `ui/Components.kt` is the whole scale for the chrome. Do not add sizes.

| Token | Size | Use |
| --- | --- | --- |
| `Title` | 24 sp, Medium | Screen titles, the home wordmark. |
| `Heading` | 17 sp, Medium | Sheet and picker headings. |
| `Body` | 15 sp | Rows, buttons, options, paragraphs (line height 22 sp). |
| `Small` | 13 sp | Secondary lines, chips, status (line height 19 sp). |
| `Label` | 12 sp, Medium, 0.12 em tracking, uppercase | Section labels. |

- Exceptions that exist and may stay: tile labels 11.5 sp, licence text 11 sp, toast 14 sp.
- Widget text is never in sp. It is sized in pixels from the box through `Fit` and `pxToSp`, so
  the phone's font-size setting cannot break a layout. Main text grows fully, secondary text at
  `Fitting.SECOND_RATE`, labels at `Fitting.SMALL_RATE`.
- One font: the phone's (Roboto). Bundled and Google fonts appear only inside widgets the owner
  chose them for.

### Spacing and shape
- Screen gutter 16 dp; `ScreenFrame` content is spaced 14 dp; card internal padding 14–16 dp.
- Radii: cards 18 dp, buttons 14 dp, chips 18 dp (pill), segmented 10 / 8 dp, tiles 12 dp, the
  sheet 22 dp top corners, the dock 26 dp, the action pill 22 dp, dialogs Material's.
- Touch targets: buttons ≥ 48 dp tall, icon actions 44 dp, chips ≥ 36 dp, swatches 48 dp around
  a 32 dp disc, resize handles 32 dp, card rows ≥ 56 dp.
- Dividers are 1 dp `Rule`; inside a `CardRow` they are inset 50 dp to clear the icon.
- The editor canvas is 412 × 848 or 848 × 412 units; widgets snap to 4 units, resize in 16-unit
  steps, never smaller than 32 units; nothing sits nearer the side than 8 units.

### Components: reuse these, do not fork them
All in `ui/Components.kt`, `ui/Icons.kt`, `ui/editor/StylePicker.kt` and `widgets/WidgetType.kt`.

- `ScreenFrame(title, onBack)` for every setup screen. `AppButton` (Primary / Secondary /
  Danger; one Primary per screen). `Chip` (status or filter). `Card` + `CardRow`.
  `SectionLabel`. `Segmented` for ≤ 4 short options; `ChoiceField` longer lists become a menu.
  `IconAction` for any icon button; `GlyphIcon` + the `Glyph` enum for every icon (add a path,
  never a drawable or an emoji/text glyph). `Rule` for dividers.
- Editor: `Tile` for any picture-choice strip; `EditorHint` / `EditorSlot` for editor-only
  placeholders (they draw nothing on the display).
- `pressable()` for taps (shrink to 0.97 for 100 ms); `glow()` only on the active thing.
- `Switch`, `RadioButton`, `Slider`, `AlertDialog`, `DropdownMenu`, `OutlinedTextField` are the
  Material ones with the scheme colours; do not restyle them per call site.
- Text that may not fit: `maxLines = 1, overflow = Ellipsis` for titles; two lines for tile
  labels; never let a button label ellipsise (shorten the words or stack the buttons).

### Motion
- Easing: `EaseOutStrong` (0.23, 1, 0.32, 1) for every tween. Durations: press 100 ms, fade
  100–160 ms, editor open 300 ms, arrive/pill 120–220 ms, pushed widgets 150 ms.
- Screen changes are opacity only (the editor opens with a 0.92 → 1 scale as the one exception).
- The sheet and anything grabbable use springs (`dampingRatio 0.85, stiffness 400`) so a grab
  mid-flight carries on from where it is.
- No looping or idle animation anywhere on the display: it must render zero frames when nothing
  changes. Android's "Remove animations" setting must leave every flow usable.

### Copy
- Plain sentences, British spelling ("colour", "licence"), no exclamation marks, no jargon
  ("Allow in settings", not "Grant permission").
- Status reads as a sentence the owner can act on ("Off: tap to set up").
- Widget placeholders say what is missing and where to fix it ("Set your city on the Home screen").

---

## 2. Performance rules

Measured on the Note 9 release build; keep these true.

1. **Zero idle frames on the display.** Only a state change may redraw. Read the minute feed, not
   the second feed, unless seconds are shown or a countdown is under an hour. Read the drifting
   burn-in offset inside `graphicsLayer {}` only.
2. **Feeds are `WhileSubscribed`.** New data sources go through `Feeds` and stop 3 s after the
   last collector. No `Handler` timers, no `WorkManager`, no partial wake locks. The only standing
   component is `AlwaysOnService`, and it holds nothing awake.
3. **No work in composition.** No side effects (assignments, haptics, starts) outside
   `SideEffect`/`LaunchedEffect`/`DisposableEffect`; `remember` anything measured or parsed;
   read `State` in draw or layout lambdas when only drawing depends on it.
4. **`Fit` is the only text-sizing path and it is expensive.** Its key must contain the settings
   and the *shape* of the content, never text that changes every second. Any change to `Fit`
   is measured with the editor-open and picker-open numbers in AUDIT.md before and after.
5. **Widgets skip when nothing changed.** `WidgetItem` and `Settings` are `@Immutable`; keep them
   so, and keep `remember(item.settings, type)` in `WidgetBody` so a drag never recomposes the
   neighbours' content.
6. **Offscreen layers are for drags.** `CompositingStrategy.Offscreen` and alpha layers cost
   graphics memory (83 MB in the editor vs 46 MB on Home); use them only where a texture is
   moved many times.
7. **Disk and network off the main thread**, with the one allowed exception of small
   `SharedPreferences` settings. Caches larger than a few KB (F1 answers, weather, prayer times)
   are files under `filesDir` written through `AtomicFile`.
8. **Lists compose once.** A fixed, short list (the 15 widget types, the 22 drivers) is a plain
   column; `Lazy*` only for lists that can be long (fonts).
9. **Images are bounded.** Notification icons 64 px, cover art 240 px on its long side, the
   circuit outline 240 points; no `Bitmap` kept past its feed.
10. **Measure before claiming.** Use the `measure` build type (release, debug key) and
    `dumpsys gfxinfo <pkg> reset` / `dumpsys gfxinfo <pkg>` around one interaction, plus
    `dumpsys meminfo <pkg>`. Debug builds are several times slower and prove nothing.

---

## 3. Code structure

### Layout of the code
| Package | Holds | Rule |
| --- | --- | --- |
| `ui/` | Home, setup screens, shared components, theme, icons | Screens take `AppGraph` and callbacks; no business logic. |
| `ui/editor/` | Editor screen, sheet, pickers, `EditorState` | All editing logic in `EditorState` (plain Kotlin, tested); composables only draw it. |
| `display/` | The three display hosts, `DisplayContent`, refresh rate, entry points | Shared behaviour goes in helpers (`windowBrightness`, `followRoomLight`, `holdCoverOff`), never copied into a host. |
| `widgets/` | One file per widget, `WidgetType`, `Fit`, drawing helpers | A widget owns its settings keys, `fields()`, `migrate()`, `normalise()` and `Content()`; it reads feeds through `LocalFeeds` only. |
| `data/` | Feeds, repositories, parsers | Pure parsing functions are `internal` and tested; network only through `Http`. |
| `store/` | Prefs, layout and snapshot repositories, codecs | Every on-disk format has a version and a migration step. |
| `layout/` | `Packer`, `DragSession`, `Retarget`, `SmartLayout` | Pure functions over `Box`/`Placed`; no Compose imports. |
| `fonts/` | Bundled fonts, Google Fonts bridge, `FontResolver` | Typefaces cached by id. |

### State
- App-wide state is a `StateFlow` in `AppPrefs` or a repository, read in Compose with
  `collectAsStateWithLifecycle()`. Screen-local state is `remember`/`rememberSaveable`.
- One source of truth per layout: `LayoutRepository.layout`. The editor writes to it on every
  change; nothing else holds a copy.
- `AppGraph` is the only place objects are wired; tests build it with fakes (`Http`, `Feeds`).

### Adding or changing things
- **A widget**: new file in `widgets/`, add to `Widgets.all`, give it `width`/`height` defaults
  that look right in the picker, add a `Spec` line in `SmartLayout.SPECS` and a category in
  `Picker.categoryOf`, and a render test in `ui/DisplayRenderTest.kt`.
- **A setting**: a `Key` on the widget object with a default; rename by adding `migrate()`,
  never by changing the stored name. Raise `AppPrefs.F1_LIVE_FORMAT`-style versions when a
  cached shape changes.
- **A setup screen**: a `Screen` enum value, a case in `App()`, a composable built from
  `ScreenFrame` + `Card`/`Section`, and a case in `ui/ScreensTest.kt`.
- **A feed**: a function returning `Flow<T>` in `data/`, a property on `Feeds` and
  `LiveFeeds` wrapped in `.shared(initial)`, a `FakeFeeds` field in `TestSupport.kt`.

### Testing
- Run `./gradlew :app:testDebugUnitTest` from `android/` before any change is called done; UI
  changes update the matching test in `ui/ScreensTest.kt`, `ui/RedesignTest.kt`,
  `ui/EditorScreenTest.kt` or `ui/DisplayRenderTest.kt`.
- Logic lives where it can be unit-tested without Compose (`EditorState`, `Packer`, parsers).
- Performance changes carry the before/after `gfxinfo` and `meminfo` lines in the commit message.

### Conventions
- KDoc on every public type and non-trivial function, written as prose about *why*.
- No reflection, no code generation, no DI framework (R8 runs with `proguard-rules.pro` empty on
  purpose).
- Kotlin names are plain English; stored ids (`"f1weekend"`, `"seg"`) never change.
- Commit messages name the user-visible effect, as the history does ("Editor: a full-width
  widget is drawn at its full width").
