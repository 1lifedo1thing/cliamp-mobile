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

## Artwork

Streams carry no cover art, so the app looks for the station's own branding: the
og:image on its homepage, then the apple-touch-icon, then the favicon the directory
recorded. About 23 of 25 top stations yield something usable. Whatever turns up is
letterboxed onto the striped plate rather than centre-cropped, because most og:images
are 1200x630 wordmarks and a square crop cuts them in half.

cliamp's own channels are excluded on purpose. cliamp.stream has an og:image, but it is
a marketing screenshot of the desktop app; the generated plate is per-channel, already
square, and reads better.

Artwork loads after playback starts, never before, so a slow homepage cannot sit between
the tap and the audio.

## Reconnecting

Live radio dies in two ways and only one of them raises an error.

A hard failure (socket dropped, server 502, DNS gone) surfaces as `onPlayerError`, after
which ExoPlayer parks in `STATE_IDLE` and never retries on its own. A silent stall keeps
the connection open but stops delivering, leaving the player in `STATE_BUFFERING`
indefinitely with no error at all. The second is the common one on a train.

`Reconnector` handles both: exponential backoff (1s, 2s, 4s, 8s, 15s, then every 30s)
for errors, a 20 second watchdog for stalls, and a `ConnectivityManager` callback so
coming back into signal retries immediately instead of waiting out the backoff. Malformed
containers and unsupported codecs are not retried, since those will never succeed.

The player shows `RECONNECTING . n` in amber while this runs. Red stays reserved for
destructive actions.

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
