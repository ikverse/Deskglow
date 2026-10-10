# Deskglow

A charging and desk screen for Android phones: a big clock, the date, your battery, notifications,
the weather, your next event and what is playing, laid out however you like. It shows by itself
while the phone charges (as Android's screen saver), or on demand with **Start now**.

Nothing runs while it is not showing: no background service, no permanent notification, no timers.

## What it does

- **Widgets:** clock, date, charging ring, battery stats (temperature, voltage, power, current,
  time to full, level, charger, health, charge cycles), notification icons, now playing, next event
  (or the next few), weather (with an hourly forecast), sun and moon, prayer times, next alarm, and
  four Formula 1 widgets: race weekend (countdown, or the last session's top 3),
  schedule, live session timing and the championship standings.
- **Settings:** each widget has its own layouts and options. Several on/off options share one row of
  chips; times follow the phone's 12 or 24-hour setting unless told otherwise; the clock, date and
  other text widgets can sit against either edge of their box. The F1 driver and team you follow are
  set once on the Home screen and every F1 widget follows them, unless it is told otherwise.
- **Editor:** drag to move, drag the corner to resize, red × to delete (with Undo). Widgets never
  overlap: anything in the way is pushed down, and slides back while you are still holding.
- **Portrait and landscape:** each has its own layout. Upright is the default; for a phone docked on
  its side there is a landscape layout, which you can edit with the phone either way up (the canvas
  is smaller when the phone is upright). The display picks the right one from the way the phone is held, and the home
  screen shows both.
- **Clock styles:** squared, seven-segment, dot matrix, outline, rounded and stacked, drawn by the
  app, plus any font.
- **Fonts:** 15 bundled, including 4 Arabic, and the whole Google Fonts library through
  **More fonts**: most popular first, with live previews. A picked font is downloaded once and kept.
- **Arabic:** the clock and date can show Arabic, in Arabic numerals (٠١٢٣) or Western ones.
- **Brightness:** dim, follow the phone, a level of your choosing, or **auto**, which matches the room's
  light sensor (listened to only while the display shows).
- **Burn-in protection:** the layout drifts a few pixels each minute.

## Setting it up on the phone

1. Install the app and open it.
2. **Start automatically when charging** → Open screen saver settings → choose Deskglow, set
   *When to start* to *While charging*, and switch it on.
3. Optional, under **Permissions**:
   - notification access, for notification icons and now playing;
   - calendar, for the next event.
4. Optional: set a **Weather city**.
5. Optional: if the phone sits on its side in a dock, open **Edit landscape layout** and arrange it.

## Building

Open `android/` in Android Studio, or from a terminal in `android/`:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The `measure` build type is the release build signed with the debug key: use it to check battery
use and smoothness, since debug builds of Compose run several times slower.

`mockup/` holds the HTML mockup the app was designed from (portrait only); open `mockup/index.html`
in a browser.

## Where things are

| Folder | What it holds |
| --- | --- |
| `android/app/src/main/java/.../widgets` | One file per kind of widget. A new widget goes here and into the `Widgets.all` list. |
| `.../data` | The data sources (battery, clock, notifications, media, calendar, weather, alarm, prayer times, Formula 1 and the light sensor). Each runs only while a widget on screen reads it. |
| `.../layout` | The no-overlap rules (`Packer`) and dragging (`DragSession`), for either canvas (`Orientation`: 412 × 848 upright, 848 × 412 on its side). |
| `.../fonts` | Bundled fonts and the Google Fonts library. |
| `.../display` | The screen saver and Start now. |
| `.../ui` | The home screen, the setup screens and the editor. |

## Data and licences

- **Weather:** data by [Open-Meteo.com](https://open-meteo.com), under CC BY 4.0. Only the chosen
  city's position is sent.
- **Prayer times:** from [Aladhan](https://aladhan.com), for where the phone is or the chosen city.
- **Sun and moon:** sunrise and sunset come with the weather; the moon's phase is worked out on the phone.
- **Formula 1:** the calendar, results and standings from the Jolpica F1 API (api.jolpi.ca); circuit
  outlines from OpenF1 and MultiViewer; live timing and session results from Formula 1's own
  live-timing feed (unofficial, and may stop working) or OpenF1. The live feed connects only while a
  session runs. Not affiliated with Formula 1.
- **Fonts:** the bundled fonts are under the SIL Open Font License 1.1. Their licences are in
  `android/app/src/main/assets/licences/` and in the app's About screen. Fonts picked from Google
  Fonts are open source as well.
- **Code:** © the author. All rights reserved.
