# Deskglow tasks

Small tickets from [AUDIT.md](AUDIT.md), each doable without design judgement by following
[GUIDELINES.md](GUIDELINES.md). One list, ordered by value. Each ticket names its files
(≤ 3), the exact change, what "done" means and how to verify. Dependencies are noted where
one ticket must land before another.

Paths are relative to `android/app/src/main/java/com/ikverse/deskglow/`.
Every ticket ends with `./gradlew :app:testDebugUnitTest` passing from `android/`.

**[PERF] measurement recipe** (used by every [PERF] ticket; build type `measure` or `release`):

```bash
adb shell dumpsys gfxinfo com.ikverse.deskglow reset
# perform the interaction named in the ticket
adb shell dumpsys gfxinfo com.ikverse.deskglow | grep -E "Total frames|Janky|percentile"
adb shell dumpsys meminfo com.ikverse.deskglow | grep -E "TOTAL:|Graphics:"
```

Baselines (Note 9, release 0.6.1) are in AUDIT.md §1.

---

## T1 [PERF] Reuse `Fit` probe slots
**Goal:** cut the cost of every `Fit` search so the editor and picker open in a few frames.
**Files:** `widgets/Fit.kt`.
**Change:**
1. Hold a `SubcomposeLayoutState` in the composable:
   `val layoutState = remember { SubcomposeLayoutState(SubcomposeSlotReusePolicy(4)) }` and
   pass it: `SubcomposeLayout(layoutState, modifier) { c -> … }`.
2. Replace `subcompose(Probe(probes++))` with a key that repeats across passes:
   `private data class Probe(val arrangement: Int, val level: Int, val step: Int)` and pass
   `(a, l, step)` from `largest()`; the two extra probes in `search()` and the memo check use
   `Probe(-1, 0, 0)` and `Probe(-2, 0, 0)`.
**Done when:** the editor still lays every widget out identically (run `ui/DisplayRenderTest.kt`
and `ui/WidgetFitRenderTest.kt`), and Home → Editor shows fewer than 6 janky frames with
p50 under 60 ms.
**Verify:** recipe above around "tap Edit layout on Home" (baseline: 6 frames, p50 150 ms,
p99 300 ms) and around "tap + in the editor" (baseline: 4 frames, p50 200 ms).
**Order:** before T2 and T4.

## T2 [PERF] A process-wide cache for `Fit` results
**Goal:** a widget measured once on any screen is not measured again for the same box.
**Files:** `widgets/Fit.kt`.
**Change:** add `private object FitCache { val map = object : LinkedHashMap<Any, Choice>(64, 0.75f, true) { override fun removeEldestEntry(e) = size > 256 } }`
keyed by `listOf(key, w, h, unit)`. In `SubcomposeLayout`, before `search()`, look the key up
(synchronised) and validate it with the existing "still fits" probe; after `search()`, store it.
Keep `FitMemo` as the per-instance fast path.
**Done when:** opening the editor after the home preview has drawn the same page runs no
`search()` for unchanged widgets (add a counter in a test: compose `DisplayContent` twice with the
same layout and assert the second pass measured ≤ 1 probe per widget).
**Verify:** recipe around Home → Editor and Editor → picker; expect further reduction from T1.
**Order:** after T1.

## T3 [UI] Map the missing Material colour roles
**Goal:** no Material default colour (violet slider track, grey dialog) shows anywhere.
**Files:** `ui/Theme.kt`, `ui/SnapshotsScreen.kt`.
**Change:** in `darkColorScheme(...)` add `secondaryContainer = Palette.Edge`,
`onSecondaryContainer = Palette.Ink`, `surfaceContainerHigh = Palette.Sheet`,
`surfaceContainerHighest = Palette.Raised`, `outlineVariant = Palette.Edge`,
`onSurfaceVariant = Palette.Muted` (already), `surfaceContainer = Palette.Sheet`. In the three
`AlertDialog`s of `SnapshotsScreen.kt` (lines ≈117, 135, 149) add `containerColor = Palette.Sheet`.
**Done when:** `ui/ThemeTest.kt` passes with a new assertion that
`MaterialTheme.colorScheme.secondaryContainer == Palette.Edge`; on the phone the Ring-thickness
slider's unlit track and the Saved-layouts dialogs are in palette greys.
**Verify:** screenshot the editor's Settings tab for the ring and the Restore dialog.

## T4 [PERF] Make the add-widget picker a plain grid
**Goal:** scrolling the picker never composes cards.
**Files:** `ui/editor/Picker.kt`.
**Change:** replace `LazyVerticalGrid(GridCells.Adaptive(160.dp), …) { items(shown, key) { … } }`
with `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp))`
containing `FlowRow(maxItemsInEachRow = 2, horizontalArrangement = spacedBy(10.dp), verticalArrangement = spacedBy(10.dp))`
and `for (type in shown) key(type.id) { Card(type) }` with each card `Modifier.weight(1f)`.
Keep the `testTag("picker grid")` on the scrolling column; `FlowRow` needs `@OptIn(ExperimentalLayoutApi::class)` as `HomeScreen` already has.
**Done when:** `ui/EditorScreenTest.kt` picker tests pass; a scroll shows under 20% janky frames.
**Verify:** recipe around "swipe the picker up one screen" (baseline: 50 frames, 72% janky,
p99 73 ms); also re-check "tap +" opening numbers do not grow beyond T1's result.
**Order:** after T1 (otherwise opening gets slower as all 15 compose at once).

## T5 [PERF] Skip the pack when the snapped box has not moved
**Goal:** a drag does one `Packer` pass per grid step, not per pointer event.
**Files:** `layout/Packer.kt`.
**Change:** in `DragSession.update`, after computing `want`: keep a `private var lastResult: Map<String, Box>?`;
if `want == last && lastResult != null` return `lastResult` immediately. Store `lastResult`
wherever `last = box` is set.
**Done when:** `layout/PackerTest.kt` passes (add a test: two `update` calls with deltas inside
the same grid cell return the same map instance), and the drag measurement improves.
**Verify:** recipe around "drag the ring 300 px down and back over ~2 s" (baseline: 72 frames,
60% janky, p50 19 ms, p90 30 ms).

## T6 [UI] Style-strip tile labels never break inside a word
**Goal:** "Seven-segment" reads on one line or breaks at the hyphen.
**Files:** `ui/editor/StylePicker.kt`, `widgets/ClockArt.kt`.
**Change:** in `Tile` set `width: Dp = 112.dp` and the label `fontSize = 11.sp`,
`letterSpacing = 0.sp`; in `ClockStyles.DRAWN` rename the label to `"7-segment"` (the id `"seg"`
stays). Update the strip's `contentPadding`/spacing only if the tests measure widths.
**Done when:** the clock's Style strip shows every label on one line on a 412 dp screen;
`ui/EditorLooksTest.kt` passes.
**Verify:** screenshot the clock settings sheet at half height.

## T7 [UI] Saved-layouts buttons fit their labels
**Goal:** no ellipsised button label.
**Files:** `ui/SnapshotsScreen.kt`.
**Change:** labels "Save now" (Primary) and "Import file" (Secondary) in the two-button row; keep
`AppButton` sizes. Update the matching text in `ui/ScreensTest.kt` if it asserts the label.
**Done when:** both labels render whole at 412 dp with the system font size at "Largest".
**Verify:** screenshot Saved layouts at default and largest font size (Settings → Display).

## T8 [PERF] Move the big caches out of SharedPreferences
**Goal:** prefs hold only small settings; large JSON answers are files.
**Files:** `store/AppPrefs.kt`, plus a new `store/CacheFiles.kt`.
**Change:** add `class CacheFiles(dir: File)` with `fun read(name): String?` and
`fun write(name, text: String?)` (delete on null) using `AtomicFile`, on the calling thread
(the repositories already run on `Dispatchers.IO`). Point `weatherCache`, `prayerCache`,
`f1Cache`, `f1Track`, `f1LiveResult` at files `weather.json`, `prayer.json`, `f1.json`,
`f1-track.json`, `f1-live.json`; keep `f1_live_result_format` in prefs. On first read of each,
fall back to the old pref key once and then `remove` it.
**Done when:** `store/F1LiveResultPrefsTest.kt`, `data/WeatherRepositoryTest.kt`,
`data/F1LiveRepositoryTest.kt` pass against the file-backed properties; the prefs XML on a device
that has used F1 shrinks to a few KB.
**Verify:** before/after `adb shell dumpsys meminfo` Java heap on Home (baseline 12.9 MB) and the
cold-start time of `MainActivity` via `adb shell am start -W com.ikverse.deskglow/.ui.MainActivity`
(record `TotalTime` three times each).

## T9 [PERF] Offscreen widget layers only while dragging
**Goal:** editor graphics memory close to Home's when nothing is being dragged.
**Files:** `ui/editor/EditorScreen.kt`.
**Change:** replace the unconditional
`.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)` on the widget box with
`.graphicsLayer { compositingStrategy = if (state.dragId != null || arrive.value < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }`.
**Done when:** the drag measurement of T5 does not regress and graphics PSS in the editor drops.
**Verify:** `dumpsys meminfo` Graphics in the editor at rest (baseline 82.8 MB) and the drag
recipe of T5 after the change.
**Order:** after T5, so drag numbers are compared like for like.

## T10 [UI] Widget names fit in the landscape side panel
**Goal:** no truncated widget names in the Widgets list when the panel is narrow.
**Files:** `ui/editor/EditorSheet.kt`.
**Change:** in `WidgetsTab` give the title `maxLines = 2` and
`style = LocalTextStyle.current.copy(lineHeight = 18.sp)`; keep `overflow = Ellipsis`.
**Done when:** "Notifications", "Charging ring", "Stat · Temperature" read whole in the side
panel on a 2220 × 1080 window; `ui/EditorLandscapeTest.kt` passes.
**Verify:** `adb shell settings put system user_rotation 1` (with `accelerometer_rotation 0`),
screenshot the landscape editor, then restore both settings.

## T11 [CODE] One helper for the three display hosts
**Goal:** `DeskglowDream`, `DisplayActivity`, `AlwaysOnActivity` share one setup path.
**Files:** `display/Displays.kt`, plus new `display/DisplayHost.kt`.
**Change:** add `internal class DisplayHost(context, window)` with `fun prepare()` (brightness
from `chosenBrightness()`, `hideSystemBars`, `setRefreshRate(fast = false)`), `fun start(scope)`
(presence started, cover lock, `followRoomLight` when Auto, exposing `roomDark: State<Boolean>`),
`fun stop()` (presence stopped, cover lock released, light job cancelled). Each host calls these
three from its own lifecycle; `AlwaysOnActivity` keeps its cover-delay logic.
**Done when:** `display/AlwaysOnTest.kt`, `display/RefreshRateTest.kt` and the display tests pass;
each host's lifecycle methods are under ten lines.
**Verify:** on the phone, Start now, the screen saver (charge the phone) and Always on (screen
off) each show the display with the chosen brightness.

## T12 [UI] Shared greys for widget tracks and rules
**Goal:** one tone each for tracks, rails and rules on the display.
**Files:** `widgets/WidgetType.kt`, `widgets/RingWidget.kt`, `widgets/StatWidget.kt`.
**Change:** add `object WidgetPalette { val Track = Color(0xFF262626); val Rail = Color(0xFF2E2E2E); val Rule = Color(0xFF333333) }`
under `Muted`; replace `Color(0xFF1B1B1B)` in `RingWidget` and the spark's `Color.Black` lerp base
in `StatWidget` with `WidgetPalette.Track`. Follow-up tickets convert `ClockWidget`,
`AlarmWidget`, `EventWidget`, `MediaWidget`, `SunMoonWidget`, `PrayerWidget`, `WeatherWidget`,
`F1*Widget` three files at a time (same rule: 1B/22/26 → Track, 2A/2E → Rail, 33/55 → Rule).
**Done when:** `grep -rn "Color(0xFF2[0-9A-F]\{5\}" widgets` returns only `WidgetPalette`'s own
lines after the follow-ups; render tests pass.
**Verify:** compare the ring, stat and alarm widgets' unlit tracks in a screenshot: one grey.

## T13 [UI] Small widgets are visible in the picker
**Goal:** Date, Notifications and Stat previews are not specks.
**Files:** `ui/editor/Picker.kt`.
**Change:** after `val scale = min(PREVIEW_SCALE, maxWidth.value * 0.94f / type.width)` add
`val floor = (maxHeight.value * 0.4f) / type.height; val shown = max(scale, min(floor, maxWidth.value * 0.94f / type.width))`
and size the box with `shown`.
**Done when:** every preview is at least 40% of the card's picture height and none overflows;
`ui/EditorScreenTest.kt` picker tests pass.
**Verify:** screenshot the picker; Date and Stat read at arm's length.

## T14 [UI] Use the check icon on the F1 screen
**Goal:** the tick matches the rest of the app.
**Files:** `ui/SetupScreens.kt`.
**Change:** in `OptionRow` replace `Text("✓", …)` with
`GlyphIcon(Glyph.Check, tint = Palette.Select, size = 18.dp, weight = 2.2f)`.
**Done when:** `ui/F1FavouriteTest.kt` passes (update any assertion on "✓" to a content check on
the selected row).
**Verify:** screenshot My F1 driver and team.

## T15 [UI] Always-on status inside the switch card
**Goal:** no orphan status line between cards.
**Files:** `ui/SetupScreens.kt`.
**Change:** delete the `if (on) { Text(...) }` block after the first `Section` and add, as the
last line of that `Section`'s `Column`,
`if (on) Text(if (missing.isEmpty()) "On" else "Needs: " + missing.joinToString(", "), fontSize = Type.Small, color = if (missing.isEmpty()) Palette.Select else Palette.Ink)`.
**Done when:** `ui/ScreensTest.kt` Always-on assertions pass with the text found inside the card.
**Verify:** screenshot Always on with the switch on.

## T16 [CODE] Haptic callback set as a side effect
**Goal:** no assignment during composition.
**Files:** `ui/editor/EditorScreen.kt`.
**Change:** wrap the `state.haptic = { … }` block in `SideEffect { state.haptic = { … } }`
(the `view` captured is the current `LocalView`).
**Done when:** `ui/editor/EditorStateTest.kt` and `ui/EditorScreenTest.kt` pass; haptics still
tick during a drag on the phone.
**Verify:** drag a widget; feel one tick per grid step.

## T17 [CODE] Rename the weather `Glyph` enum
**Goal:** one `Glyph` type in the codebase.
**Files:** `widgets/WeatherArt.kt`, `widgets/WeatherWidget.kt`.
**Change:** rename `enum class Glyph` in `WeatherArt.kt` to `DetailSymbol` and `DetailGlyph`'s
parameter accordingly; update the `details()` signature and uses in `WeatherWidget.kt`.
**Done when:** the project compiles with no `widgets.Glyph`; `data/WeatherRepositoryTest.kt` and
render tests pass.
**Verify:** `grep -rn "widgets.Glyph" android/app/src` is empty.

## T18 [CODE] README states the Always-on exception
**Goal:** the README does not promise what Always on breaks.
**Files:** `README.md`.
**Change:** line 7 becomes "Nothing runs while it is not showing: no background service, no
permanent notification, no timers, unless you switch on **Always on**, which keeps a small
service waiting for the screen to turn off."
**Done when:** the sentence reads as above.
**Verify:** read it.

## T19 [PERF] Parse icon paths once per glyph
**Goal:** no per-instance path parsing.
**Files:** `ui/Icons.kt`.
**Change:** give `Glyph` two `by lazy` properties `strokePaths: List<Path>` and
`fillPaths: List<Path>` built with `PathParser().parsePathString(it).toPath()`; `GlyphIcon`
uses them and drops its two `remember` lines.
**Done when:** `ui/RedesignTest.kt` passes; Home first frame unchanged or faster.
**Verify:** recipe around "open the app to Home" (`am start -W`, record `TotalTime` three times
before and after).

## T20 [PERF] Cheap thumbnails on Saved layouts
**Goal:** the screen's cost does not grow with the number of snapshots.
**Files:** `ui/SnapshotsScreen.kt`.
**Change:** replace `Thumb`'s `DisplayContent` with a `Canvas` that draws each visible widget's
box as a rounded rectangle filled with its `Common.COLOUR` at 35% alpha on black, scaled from
the orientation's canvas to the thumb.
**Done when:** `store/SnapshotRepositoryTest.kt` and the Saved-layouts case in
`ui/ScreensTest.kt` pass; with five snapshots saved, opening the screen shows no frame over 50 ms.
**Verify:** save five snapshots, recipe around "tap Saved layouts on Home".

## T21 [CODE] One `rememberResumeCount()`
**Goal:** the five copies of the resume counter become one helper.
**Files:** `ui/Components.kt`, `ui/HomeScreen.kt`, `ui/SetupScreens.kt`.
**Change:** add to `Components.kt`
`@Composable fun rememberResumeCount(): Int { var n by remember { mutableIntStateOf(0) }; LifecycleResumeEffect(Unit) { n++; onPauseOrDispose {} }; return n }`
and use `val resumes = rememberResumeCount()` in the five places, keeping the extra
`startAlwaysOn` calls in their own `LifecycleResumeEffect`.
**Done when:** `ui/ScreensTest.kt` passes; behaviour on return from system settings unchanged.
**Verify:** grant notification access in system settings, return: the Permissions card updates.

## T22 [UI] Home works on its side
**Goal:** Start Deskglow and the chips are visible without scrolling in landscape.
**Files:** `ui/HomeScreen.kt`.
**Change:** wrap the body in `BoxWithConstraints`; when `maxWidth > maxHeight` lay out a `Row`
with the `PreviewCard` (weight 1, pager height `maxHeight - 140.dp`) on the left and a scrolling
`Column` (weight 1) with the button, chips and cards on the right; otherwise keep today's column.
**Done when:** `ui/EditorLandscapeTest.kt`-style test for Home at 848 × 412 dp finds the Start
button on screen; portrait is unchanged pixel for pixel in `ui/RedesignTest.kt`.
**Verify:** screenshot Home in landscape (same rotation commands as T10).

## T23 [PERF] Release the last cover art with the media feed
**Goal:** no bitmap outlives the media session.
**Files:** `data/Media.kt`.
**Change:** move `lastSource`/`lastArt` into a small `private class ArtCache` created inside
`mediaUpdates` and passed to `trackOf`/`artOf`; it is garbage with the flow.
**Done when:** `data/DataParsingTest.kt` passes; no top-level `var` remains in `Media.kt`.
**Verify:** `dumpsys meminfo` Graphics/Java on Home after playing then stopping music, before
and after the change.
