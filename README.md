# cliamp mobile

Terminal soul, native body. An Android radio player for
[cliamp radio](https://radio.cliamp.stream) and the 53,000-station
[Radio Browser](https://www.radio-browser.info/) directory.

Built from the `Cliamp Mobile.dc.html` concept: one monospace face, hairline rules
instead of cards, phosphor green as the only accent, and transport keys with real
mechanical travel.

## Build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 21 (set in `gradle.properties`), Android SDK 36, and a `local.properties`
with `sdk.dir`. Release builds are signed with the debug key; swap `signingConfig` in
`app/build.gradle.kts` before shipping anything.

## What it does

| Screen | What is on it |
| --- | --- |
| PLAY | Station, live ICY track title, brick meter, `--- STREAMING ---` rule, transport |
| LIB | cliamp's 12 channels with live listener counts, then the directory, paged 60 at a time |
| QUEUE | Up next, favourites, history |
| :CMD | Command bar with its own keyboard: `:play`, `:tag`, `:country`, `:eq`, `:random` |
| SCOPE | 32-column spectrum off the real FFT, 7-band equaliser, presets |
| STATS | cliamp radio listeners, 31-day session history, top countries and cities |
| SETTINGS | Buffer depth, cellular, haptics, visualiser, dark/light/system palette |

Playback runs in a `MediaSessionService`, so the lockscreen, notification and Bluetooth
controls all drive the same player.

## Notification

Radio has no cover art, so the app draws its own: the striped plate and the phosphor
mark, generated per station at 512px. That is not decoration. Android derives the media
player's background and accent from the artwork, so with no artwork the notification is
grey system chrome and with it the whole chip picks up the green.

A one-item queue also means Media3 offers no prev/next, which leaves a single lonely
play button. Three custom session commands replace them: previous station, favourite,
next station. Station stepping walks your favourites, or cliamp's channels if you have
none, exactly as the widget does.

## Home screen

A resizable widget and a quick settings tile. Radio is worth a widget in a way a music
library is not: you nearly always want the same few stations, so the widget skips
browsing entirely.

| Size | What is on it |
| --- | --- |
| 2x1 | Station and play/pause |
| 3x1 | Adds the live track, prev/next, and the `--- STREAMING ---` rule |
| 4x2 | Adds quick-tune chips for your favourites, or cliamp's channels if you have none |

Add it from Settings -> Home screen -> Add widget, or from the launcher's widget picker.
The tile goes in from the quick settings editor.

Widget taps go through a `MediaController` rather than `startForegroundService`. The
intent route looks cheaper, but it gives the service five seconds to call
`startForeground` and tuning a station has to read preferences and follow a playlist
redirect first; on a slow connection that window closes and the system kills the app.

## Sources

- `radio.cliamp.stream/streams.m3u` seeds the channel list at launch. The twelve
  built-ins in `CliampRadio.kt` are the offline fallback.
- `radio.cliamp.stream/statistics` supplies live listener counts and daily history.
- `all.api.radio-browser.info` is resolved at runtime for the directory, with failover
  across mirrors. Plays are reported back to their click counter.

## Design

`docs/design.md` in the concept folder is the source of truth for palette, type scale,
control geometry and the brick meter. Colours in `ui/theme/Palette.kt` are straight
sRGB conversions of the concept's oklch values.

Two rules worth repeating: amber means "this lives on a remote host" and nothing else,
red is only ever destructive, and there is never a third accent hue.

## Licence

Proprietary. All rights reserved, see `LICENSE`. No permission is granted to use,
copy, modify or redistribute this code.

Bundled third-party components keep their own terms, listed in
`THIRD-PARTY-NOTICES.md`. JetBrains Mono ships under the SIL Open Font License,
so `licenses/JetBrainsMono-OFL.txt` has to travel with any build.
