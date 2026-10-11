# Deskglow audit — UI, performance, implementation

Audited: version 0.6.1 (commit 7de10e4), 11 October 2026.
Device: Samsung Galaxy Note 9 (SM-N960F), Android 10, 1080 × 2220 at 420 dpi, 60 Hz panel.
Build measured: the signed release build the phone had installed (0.6.1, R8 minified), so numbers
are not inflated by a debug Compose runtime.
Method: every screen, component, theme, data and background-work file in `android/app/src/main`
was read; each screen was opened on the phone over adb and captured with `screencap`; frame
times came from `dumpsys gfxinfo` (reset before each interaction) and memory from `dumpsys meminfo`.

Companion documents: [GUIDELINES.md](GUIDELINES.md) (the rules any change must follow) and
[TASKS.md](TASKS.md) (tickets, ordered by value).

---

## 1. Baseline measurements

Frame times are from `dumpsys gfxinfo com.ikverse.deskglow` after a reset, so each row covers only
the interaction named. "Janky" is Android's own count of frames over 16 ms.

| Screen / interaction | Frames | Janky | p50 | p90 | p95 | p99 | Notes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Home: scroll to the bottom and back | 124 | 13 (10%) | 7 ms | 16 ms | 17 ms | 20 ms | Smooth. "High input latency" 114 is the panel's touch sampling, not the app. |
| Home → Editor (the opening transition) | 6 | 6 (100%) | 150 ms | 300 ms | 300 ms | 300 ms | Six frames for a 300 ms animation: the editor's first composition is the cost. |
| Editor: drag the ring down 300 px and back | 72 | 43 (60%) | 19 ms | 30 ms | 32 ms | 42 ms | Slow UI thread 17, slow draw 8, deadline missed 19. |
| Editor: scroll the settings sheet (ring, full height) | 57 | 41 (72%) | 17 ms | 20 ms | 24 ms | 36 ms | |
| Editor → Add-widget picker (opening) | 4 | 4 (100%) | 200 ms | 250 ms | 250 ms | 250 ms | 15 live widget previews composed at once. |
| Picker: scroll one screen | 50 | 36 (72%) | 18 ms | 32 ms | 34 ms | 73 ms | Cards compose as they scroll in (lazy grid). |
| Display (portrait, screen 2: one F1 widget), 30 s idle | 0 | 0 | – | – | – | – | Nothing redraws while nothing changes. |
| Display (portrait, same screen), second 30 s run | 2 | 2 | 36 ms | – | – | – | The two frames are the minute tick. |
| Display (landscape screen with F1 weekend, schedule, live result), 30 s idle | 0 | 0 | – | – | – | – | |

Memory (`dumpsys meminfo`, PSS in KB):

| State | Total | Java heap | Native heap | Graphics |
| --- | ---: | ---: | ---: | ---: |
| Home | 103,124 | 12,888 | 19,144 | 45,828 |
| Editor, settings sheet open (ring) | 166,260 | 17,352 | 38,040 | 82,799 |
| Editor, clock settings at half height | 181,694 | 25,176 | 44,248 | 83,382 |
| Add-widget picker | 172,443 | 28,556 | 38,176 | 76,785 |
| Display, portrait (from a cold home) | 136,716 | 18,712 | 39,440 | 49,829 |
| Display, portrait (opened right after the editor) | 167,744 | 21,484 | 36,632 | 76,497 |
| Display, landscape | 121,902 | 15,284 | 38,244 | 40,292 |

Other baselines:

- Wake locks while the display showed: only the `SCREEN_BRIGHT_WAKE_LOCK` that WindowManager
  holds for the window's `FLAG_KEEP_SCREEN_ON`. No partial wake lock from the app. CPU 0% in `top`.
- Panel mode votes: the app leaves the Note 9's single 60 Hz mode alone (`RefreshRate.kt` finds no choice).
- Release APK: 3.7 MB, of which 1.24 MB is the 15 bundled fonts (Amiri alone 369 KB).
- Services alive while the app is open: `NotificationWatcher` (bound while a widget reads
  notifications, unbinds 3 s after) and `AlwaysOnService` (the owner has Always on switched on).

Not measured, and why:

- Two-finger swipes on the display: `sendevent` is denied to the shell user on this phone and
  `input` is single-touch, so the display could not be paged to screen 1. Its idle numbers are for
  the screen the owner had left it on (one F1 one-line widget) and the landscape screen that was open.
  Screen 1 was observed only through the editor and home preview, which render the same widgets.
- The always-on activity (it would lock the phone mid-audit), an F1 session live (none was running),
  the Google Fonts sheet (it downloads to the phone), and screen-saver (dream) mode.

---

## 2. Screen inventory: what each control actually does

Navigation is a single activity with a `Screen` enum and an `AnimatedContent` fade
([MainActivity.kt:66](../../android/app/src/main/java/com/ikverse/deskglow/ui/MainActivity.kt)).
Back from any setup screen returns Home; the editors handle Back themselves.

### Home (`ui/HomeScreen.kt`)
- Title "Desk**glow**" (amber "glow"). Decorative.
- Preview card: a `HorizontalPager` of live `DisplayContent` pages, fixed 330 dp tall
  (line 172). Tapping a page or **Edit layout** opens the editor on that page. Dots show the page.
  A `Segmented` control switches portrait/landscape, each pager keeping its own page.
- **Start Deskglow** (primary button): starts `DisplayActivity` (full screen, keep-screen-on).
- Status chips: screen-saver status (reads `Settings.Secure` on every resume, line 85), Always on,
  Brightness (+ "burn-in off"), weather city, F1 driver. Each opens its setup screen.
- Cards: Portrait layout / Landscape layout (open editor page 0; detail is the page count), Saved
  layouts, Permissions (text recomputed per resume), About.
- On every resume `startAlwaysOn` is called again if the setting is on (line 82).

### Setup screens (`ui/SetupScreens.kt`)
- **Start automatically when charging**: status line + instructions; the button opens
  `ACTION_DREAM_SETTINGS` (falls back to Settings home if missing).
- **Always on**: switch writes the pref and starts/stops the foreground service; "Display over other
  apps", "Unrestricted battery use", "Notifications" cards each open the matching system page or
  permission prompt; button labels change to "Allowed" once granted.
- **Permissions**: notification access opens the listener settings; calendar asks once via the
  runtime prompt, then sends to App info.
- **Weather city**: "Use my location" switch (asks coarse location once); search field →
  Open-Meteo geocoding; tapping a result sets the city and goes back; "Remove city".
- **My F1 driver and team**: two option lists; the roster is the built-in grid until the first
  standings answer replaces it (`F1Roster.update`).
- **Brightness and burn-in**: four radio modes (the slider appears for Custom), burn-in,
  cover-off and blank-in-dark switches.
- **About and licences**: version, data credits, tap a bundled font to expand its licence text.

### Saved layouts (`ui/SnapshotsScreen.kt`)
Name field (proposed from the time), Save (both orientations, all pages), Import from file (SAF),
per-snapshot thumbnails of page 1 of each orientation, Restore (saves "Before restore" first),
Rename, Export (SAF create document), Delete. Dialogs confirm restore and delete.

### Editor (`ui/editor/*`)
- Top bar: Back (also pops picker → selection → select mode first), screen switcher dropdown
  (tap to switch, hold-and-drag to reorder, add/delete), Undo/Redo (50 steps; a drag is one step,
  slider changes within 1 s merge), ⋯ menu (Select widgets, Snap all to grid, Copy to the other
  orientation with a confirm dialog, Reset with a confirm dialog).
- Stage: the canvas fitted to the space left; pinch zooms to 6×, "2.0× · Reset" chip appears.
  Tap selects, second tap opens settings, hold starts select mode, drag moves (pushes others, they
  glide back while held), four corner handles resize, centre guides snap, haptics per grid step.
- Action pill over the selected widget: settings, duplicate, hide, delete (with Undo toast).
- Dock: + (picker), Auto-arrange (cycles compositions, toast with Undo), Snap all to grid.
- Sheet (portrait) at peek/half/full with spring settle; side panel in landscape. Widgets tab
  (live thumbnails, eye, bin, Add widget); Settings tab (Layout / Content / Look sections built
  from each widget's `fields()`, layout tiles are live drawings, colour swatches + HSV dialog,
  style strip with "More fonts" → Google Fonts sheet).
- Picker: search box, category chips, 15 live previews at one scale; tap adds (Packer finds room).
- Portrait editing locks the screen upright; the landscape layout can be edited either way.

### The display (`display/Displays.kt`, `DisplayContent.kt`)
One `LiveDisplay` composable serves three hosts: the screen saver (`DeskglowDream`), Start now
(`DisplayActivity`), and Always on (`AlwaysOnActivity`). Single tap does nothing, double tap
closes, triple tap opens the app; two fingers sideways change screen and show dots for 3 s;
the panel is asked for its fastest mode only during the swipe. Burn-in shifts the whole layer
by ≤ 4 dp once a minute without recomposing anything. Brightness modes: dim (0.02), system,
custom level, auto (light sensor, smoothed, only while shown). Cover-off holds a proximity
wake lock. Blank-in-dark fades to black after 10 min dark.

### Widgets (`widgets/*`, one file each, listed in `Widgets.all`)
Clock, Date, Notifications, Ring, Stat, Weather, Sun & moon, Next event, Now playing, Prayer
times, Next alarm, F1 race weekend, F1 schedule, F1 live session, F1 standings. Each reads only
the feeds it needs (`LocalFeeds`), sizes text from its box via `Fit`, and shows hints/slots only
in the editor (`LocalEditing`). Every setting is a typed `Key` with a default and `migrate()`
for renamed settings; unknown settings pass through untouched.

### Background work (`data/*`, `DeskglowApp.kt`)
All feeds are `stateIn(WhileSubscribed(3 s))`: they run only while a widget on screen collects
them. Minute ticks come from `ACTION_TIME_TICK`, seconds from a `delay` loop, battery from the
sticky broadcast (+ a 5 s poll only for power/current/time-to-full), notifications through a
listener that unbinds itself when unwatched, calendar through a content observer, weather and
prayer times every 30 min (cached in prefs), F1 calendar weekly / standings daily or 6-hourly
on weekends, F1 live timing over SSE only inside a session window. Always on is the one true
background component: a foreground service that listens for screen-off.

---

## 3. What works well and must stay

- **The energy model.** Nothing runs when the display is not showing; every feed is
  subscription-driven; the idle display renders zero frames; no timers, no partial wake locks.
  This is the app's core promise and the numbers confirm it.
- **One canvas in dp units for every phone** (412 × 848 / 848 × 412) with `Fit` sizing text from
  the box, so the editor, the home preview, the picker and the display all draw the same thing.
- **The editor's model**: `EditorState` is plain Kotlin with full history, `Packer`/`DragSession`
  are pure functions with tests, and every change is written straight to the shared repository so
  the preview and the display never disagree.
- **Widgets as self-contained objects** (`WidgetType`): settings, fields, migrations and drawing
  in one file; adding a widget touches one list.
- **Resilient data layer**: `runCatching` + typed Failed states + last-good cache + retry delays
  everywhere; versioned layout/snapshot codecs with a migration hook; unreadable files are set
  aside, never overwritten.
- **The visual language**: warm near-black surfaces, one amber meaning "active", a five-size type
  scale, 44/48 dp targets, icons drawn in code, a strong ease-out at 100–300 ms, opacity-only
  screen changes. The dark theme is forced so bars and text are always right.
- **Accessibility work already done**: content descriptions, custom actions for move/resize,
  live regions on toasts, 48 dp swatch targets, selected/role semantics.
- **Tests**: a large Robolectric/Compose test suite for screens, editor, feeds and parsers.

---

## 4. Findings

Severity: high / med / low. Effort: S (under an hour, ≤ 3 files), M (half a day), L (more).
Paths are relative to `android/app/src/main/java/com/ikverse/deskglow/`.

### Performance

**P1 [PERF] high · S — `Fit` composes every probe from scratch.**
Area: every widget, so the editor, picker, home preview and saved-layout thumbnails.
`widgets/Fit.kt:123` creates a `SubcomposeLayout` with the default state (no slot reuse) and
`widgets/Fit.kt:145` subcomposes each probe under a fresh key (`Probe(probes++)`), up to 9 steps
per arrangement/level (`Fit.kt:161`). Each probe is a brand-new composition of the widget's
content (text measuring included) that is disposed at the end of the pass. Evidence: Home →
Editor is 6 frames at 150–300 ms; the picker opens with 4 frames at 200–250 ms; picker scrolling
hits 73 ms frames as cards compose. Fix: give the `SubcomposeLayout` a
`SubcomposeLayoutState(SubcomposeSlotReusePolicy(n))` so probe nodes are reused, and use stable
probe keys per (arrangement, level, step) so repeat passes hit the same slots.

**P2 [PERF] high · M — the `Fit` result is remembered per composable instance only.**
`widgets/Fit.kt:91–96, 219–231`: `FitMemo` lives in `remember {}`. Leaving and re-entering a
screen, or a lazy grid recycling a card, throws the result away and the search runs again for
the same (key, width, height). Fix: a small process-wide LRU keyed by (key, w, h, unit) in
`Fitting`, consulted before `search()`. The display then opens with the editor's results and
vice versa.

**P3 [PERF] med · S — the picker is a lazy grid of 15 fixed items.**
`ui/editor/Picker.kt:98` uses `LazyVerticalGrid`, so each card is composed (and its `Fit`
search run) as it scrolls into view: 72% janky, p99 73 ms. With 15 items that never change, a
plain two-column `FlowRow` inside `verticalScroll` composes once at open and scrolls at the
panel's rate.

**P4 [PERF] med · S — a drag recomputes the pack on every pointer event.**
`ui/editor/EditorState.kt:281` → `layout/Packer.kt:255`: each `onDrag` runs `DragSession.update`
(a full `resolveGroup`, and a 6-step bisection when blocked) and the haptic check, even when the
snapped box has not moved since the last event. The Note 9 reports touch at 120 Hz, so this is
twice per frame. Evidence: 60% janky, p50 19 ms during a drag. Fix: in `DragSession.update`
return the previous result when the snapped `want` equals `last`.

**P5 [PERF] med · M — large JSON blobs live in SharedPreferences.**
`store/AppPrefs.kt:166–192`: the weather cache, prayer cache, whole F1 answer set (calendar,
results, two standings, two previous standings ≈ 70 KB), track outline and live result are
`putString`s. Every change (weather every 30 min) rewrites the whole XML file, and the file is
parsed on the main thread at first access on each launch. Fix: keep them as files under
`filesDir` through the same `AtomicFile` pattern `LayoutRepository` already uses; leave prefs
for small settings.

**P6 [PERF] med · S — every editor widget is an offscreen layer all the time.**
`ui/editor/EditorScreen.kt:528` applies `CompositingStrategy.Offscreen` to each widget so a
drag only moves textures. Graphics PSS in the editor is 83 MB against 46 MB on Home, and a
display opened straight after the editor still held 76 MB. Keep the strategy, but apply it only
while `state.dragId != null` (or while the arrive animation runs), so the textures exist only
when they pay for themselves. Verify the drag numbers do not regress.

**P7 [PERF] low · S — icon paths are parsed per icon instance.**
`ui/Icons.kt:61–62`: `GlyphIcon` parses the path strings in `remember(glyph)` for every call
site. Parse once per enum value (a `lazy` on the entry) and the home screen's dozen icons share it.

**P8 [PERF] low · S — `pressable` adds a graphics layer to every tappable.**
`ui/Components.kt:67–79`: `composed` + `animateFloatAsState` + `graphicsLayer` on every row,
chip, tile and button. Harmless at today's counts; if lists grow, turn it into a `Modifier.Node`
and only add the layer while pressed.

**P9 [PERF] low · M — Saved layouts cost grows with every snapshot.**
`ui/SnapshotsScreen.kt:90–91` draws two full `DisplayContent` trees per snapshot (every widget
running `Fit` at 30 dp wide) and `store/Snapshots.kt:67,105` reads every file on the main
thread at construction. Fine at one snapshot; with ten it is the slowest screen in the app. Fix:
cheaper thumbs (boxes in the widget colours, or a cached bitmap per snapshot) and load off main.

**P10 [PERF] low · S — the last cover art is kept forever.**
`data/Media.kt:88–89`: top-level `lastSource`/`lastArt` hold the last scaled cover (≈ 230 KB)
after the media feed has stopped. Clear them in `mediaUpdates`'s `awaitClose`.

**P11 [PERF] low · S — layout files are read on the main thread.**
`store/LayoutStore.kt:93`: each `LayoutRepository` reads and decodes its file in its constructor.
Up to ten small files across pages and orientations, so a few ms each; worth moving to IO only
if the home screen's first frame becomes a problem.

### UI

**U1 [UI] med · S — style tile labels break mid-word.**
`ui/editor/StylePicker.kt:149,163`: tiles are 104 dp wide with 6 dp padding and an 11.5 sp
label; "Seven-segment" renders as "Seven-segmen" / "t" (screenshot of the clock's Style strip).
Fix: 112 dp tiles or a label of 11 sp with `letterSpacing = 0`, and a shorter label ("7-segment").

**U2 [UI] med · S — "Save current layouts" truncates.**
`ui/SnapshotsScreen.kt:77`: two buttons share the row at 412 dp; the primary shows
"Save current layo…". Fix: "Save now" / "Import", or stack the buttons.

**U3 [UI] med · S — Material defaults leak through the palette.**
`ui/Theme.kt:49–60` sets ten colour roles only. `Slider` (inactive track) and `Switch` fall back
to Material's dark `secondaryContainer` (#4A4458, a violet grey visible under "Ring thickness"),
and the three `AlertDialog`s in `ui/SnapshotsScreen.kt:117,135,149` use the default dialog
container while the editor's dialogs pass `Palette.Sheet`. The unfocused `OutlinedTextField`
border is `outline = Palette.Rule` (#26231F), nearly invisible on the City and Saved layouts
screens. Fix: set `secondaryContainer`, `onSecondaryContainer`, `surfaceContainerHigh(est)`,
`outlineVariant` in the scheme, pass `containerColor = Palette.Sheet` to those dialogs, and use
`Palette.Edge` for text-field borders.

**U4 [UI] low · S — landscape side panel truncates widget names.**
`ui/editor/EditorSheet.kt:251–258` (64 × 40 dp thumb, single-line title) inside a panel capped
at 320 dp (`EditorScreen.kt:284`): "Notificatio…", "Charging ri…", "Stat · Tem…". Fix: allow two
lines, or a 48 × 30 dp thumb when the panel is narrower than 300 dp.

**U5 [UI] low · M — Home on its side shows only the preview.**
`ui/HomeScreen.kt:172` fixes the pager at 330 dp; in landscape the title and preview fill the
screen and Start Deskglow is below the fold. Fix: in landscape put the preview beside the button
and chips (two columns), or size the pager from the available height.

**U6 [UI] low · S — small widgets look empty in the picker.**
`ui/editor/Picker.kt:69,116`: one scale (0.36) for every preview; Date, Notifications and Stat
become 12 px specks in a 96 dp black box. Keep proportion between cards, but floor the scale so
a preview is at least ~40% of the box height.

**U7 [UI] low · S — a text tick where the rest of the app uses an icon.**
`ui/SetupScreens.kt:327` draws "✓" as text; the editor's screen rows use `GlyphIcon(Glyph.Check)`.

**U8 [UI] low · S — an orphan status line on Always on.**
`ui/SetupScreens.kt:125–131`: "On" / "Needs: …" sits alone between two cards. Fold it into the
switch card as the small line under the description.

**U9 [UI] low · S — seven near-black greys on the widget side.**
`grep` finds 0xFF1B1B1B, 1E1E1E, 222222, 262626 (×4), 2A2A2A (×3), 2E2E2E (×5), 333333 used for
tracks, rules and rails across the widgets; `widgets/WidgetType.kt:142` defines only `Muted`.
Define `WidgetPalette.Track`/`Rule`/`Rail` and use them, so rings, bars and arcs share one tone.

**U10 [UI] info — contrast.**
`Palette.Muted` on `Palette.Sheet` is 5.0 : 1 (passes AA for body text); the 0.7-alpha chevrons
≈ 3.5 : 1 (fine for icons); widget `Muted` on black is 6.2 : 1. "Dim finished sessions" at 0.4
alpha is ≈ 1.5 : 1 by design. No change needed.

### Implementation

**C1 [CODE] med · M — three display hosts repeat the same setup.**
`display/Displays.kt:335,409,463`: `DeskglowDream`, `DisplayActivity` and `AlwaysOnActivity`
each set brightness (`windowBrightness(...)?.let` at 418 and 478), start `followRoomLight`,
hide system bars, pick the refresh rate, hold the cover lock and report presence. A small
`DisplayHost` helper (window + lifecycle in, one `attach()` call) removes the triplication and
makes the next host (a wear-style tile, a second display) trivial.

**C2 [CODE] low · S — a side effect in composition.**
`ui/editor/EditorScreen.kt:178`: `state.haptic = { … }` runs on every recomposition. Wrap in
`SideEffect`, or pass the haptic callback into `remember { EditorState(...) }`.

**C3 [CODE] low · S — two enums called `Glyph`.**
`ui/Icons.kt:30` (app icons) and `widgets/WeatherArt.kt:185` (weather detail symbols). Rename
the weather one `DetailSymbol`; today a wrong import compiles silently.

**C4 [CODE] low · M — all UI text is Kotlin literals.**
No `stringResource`/`R.string` use in any `.kt` file (only the widget and tile labels are
resources). Arabic users get Arabic widgets with English chrome. This is a conscious scope
choice; note it here so a future localisation is a single, planned move, not a drip.

**C5 [CODE] low · S — README promise vs Always on.**
`README.md` line 7 says "no background service, no permanent notification"; with Always on
switched on there is a foreground service and an `IMPORTANCE_MIN` notification
(`display/AlwaysOn.kt:256–277`). Add "unless Always on is switched on" to the README.

**C6 [CODE] low · S — five copies of the resume counter.**
`ui/HomeScreen.kt:79–84` and `ui/SetupScreens.kt:83–88, 103–108, 165–169, 230–234` each keep a
`resumes` int in a `LifecycleResumeEffect`. One `rememberResumeCount()` in `Components.kt`.

**C7 [CODE] low · S — `f1Live` subscriber counting is clever but hidden.**
`DeskglowApp.kt:190–208` wraps a `StateFlow` to count collectors and flip `detail`. It works
and is tested; a one-paragraph comment on why `SharingStarted` could not do it would save the
next reader an hour.

**C8 [CODE] info — what is not a problem.**
Structure (ui / display / widgets / data / store / layout / fonts) is clear; state is plain
`StateFlow` + `mutableStateOf`, no framework; errors are handled uniformly; JSON by hand with
`org.json` is justified by the no-reflection R8 setup; no dead code was found beyond the
retired flag keys that `migrate()` still reads on purpose.
