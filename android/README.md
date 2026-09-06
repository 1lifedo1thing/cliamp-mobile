# cliamp mobile

Terminal soul, native body. An Android radio player for
[cliamp radio](https://radio.cliamp.stream) and the 53,000-station
[Radio Browser](https://www.radio-browser.info/) directory.

Built from the `Cliamp Mobile.dc.html` concept: one monospace face, hairline rules
instead of cards, phosphor green as the only accent, and transport keys with real
mechanical travel.

## Install

Grab the APK from [releases](https://github.com/bjarneo/cliamp-mobile/releases), or:

```sh
gh release download --repo bjarneo/cliamp-mobile --pattern "*.apk"
adb install -r cliamp-*.apk
```

## Releases

Two workflows, first-party actions only.

`build.yml` compiles debug and release on every push. Release is in there on purpose:
R8 only runs on release, and a missing keep rule compiles clean then dies at startup.

`release.yml` fires on a `v*` tag, builds a signed APK and attaches it to a GitHub
release. Version comes from the tag, `versionCode` from the run number, so each build
installs over the last.

```sh
git tag -a v0.0.2 -m "cliamp 0.0.2"
git push origin v0.0.2
```

Signing uses a stable release key held in repository secrets, not the debug key. CI
generates a fresh debug keystore every run, so debug-signed releases would each carry a
different key and could never be upgraded over. The workflow refuses to publish an APK
whose certificate reads `CN=Android Debug`.

`cliamp-release.jks` and `keystore.properties` are gitignored and exist only on the
author's machine. Losing both means no future build can install over an existing one.

## Build

This is one workspace of a monorepo; everything below runs from `android/`.

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
| SERVERS | Music servers: add one through a wizard, then browse and play it |
| SETTINGS | Buffer depth, cellular, haptics, visualiser, dark/light/system palette |

Playback runs in a `MediaSessionService`, so the lockscreen, notification and Bluetooth
controls all drive the same player.

## Providers

Point cliamp at a music server you own. Navidrome first, and because it speaks Subsonic
the same client covers gonic, airsonic and other Subsonic servers.

Adding one runs a wizard: server URL, username, password, then a probe against
`/rest/ping.view`. Nothing is written until the server answers, so a typo fails in the
wizard rather than surfacing later as an empty library. Browsing gives newest, most
played, A-Z, artists and starred, drilling into an album's tracks; playing a track queues
the whole album.

Two things worth knowing about the design:

- **The spec is declarative**, ported from cliamp desktop's `cmd/setup.go`. A provider is
  a name, an intro, a list of fields and a validate function, so adding the next one is a
  data change rather than a screen. `FieldSpec.onlyIf` is carried over because Jellyfin
  accepts either an API token or user plus password.
- **Credentials go through the Android Keystore**, AES-256-GCM, not the preferences
  DataStore the settings use. Subsonic signs every request with `md5(password + salt)`, so
  the password has to stay retrievable rather than being traded for a token once. For the
  same reason a track's stored url is an opaque `cliamp-provider://` reference signed at
  play time: a real stream URL embeds a token that never expires, and persisting one to
  history would put a replayable credential in a plain file.

### SSH / SFTP

The one provider that is not a music server. Give it a host, a password or a pasted
private key, and one music folder per line; add as many hosts as you have. Leave the
folder field empty and the probe looks in `~/Music`, `/srv/music` and the other usual
places and fills in what it finds.

There is no album endpoint on a filesystem, so the tree is walked once over SFTP and
kept in `sftp_tracks`. Tags are not read: pulling the header of every file would be tens
of thousands of round trips, and `Artist/Album/01 - Title.flac` already says all of it.
Rows are written as they are found, so the library fills in while the walk is still
going, and a rescan replaces rather than merges - anything still carrying the previous
scan's id at the end is what has since been deleted from the server.

Playback is a real stream, not a download: `SftpDataSource` opens the remote file at
`DataSpec.position`, so seeking inside a track works and nothing is staged on disk.
Connections are pooled three per host - the playing track, the one the player primes
behind it, and a scan alongside both.

Tailscale SSH works and needs no credentials at all: pick `Tailscale` in the wizard and
give it a host and a username. `tailscaled` terminates the connection itself and
authenticates on tailnet identity - the WireGuard session is the credential - so it
offers no password or public-key method, only `none`. The client sends `none` first in
every case, the way OpenSSH's own does, so a Tailscale host works whichever credential
type an account was set up with. It does serve the SFTP subsystem, so browsing and
streaming are unchanged.

Two things to get right on the Tailscale side: the phone needs to be on the tailnet (the
Tailscale app is a system VPN, so cliamp's socket rides it like any other app's), and the
ACL wants `"action": "accept"` rather than `"check"` - check mode asks for a browser
re-auth that a background media player has nowhere to show.

Host keys are trust-on-first-use and pinned afterwards. The first probe shows the
fingerprint in OpenSSH's own format, so it can be checked against `ssh-keygen -lf` on the
server, and it is stored against the host it was seen on - repointing an account at a
different machine is a different host, not a key change.

`sshj` and BouncyCastle cost about 2.3 MB of the release APK. Android ships a cut-down
BouncyCastle under the name `BC` that is missing most of what a current key exchange
needs, so the SSH layer removes it and registers the real one; TLS still goes through
Conscrypt and provider secrets still go through `AndroidKeyStore`.

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

`../docs/design.md` is the source of truth for palette, type scale, control geometry and
the brick meter, read out of `ui/theme/` rather than out of the concept. `../docs/concept.md`
is the original spec and `../docs/Cliamp Mobile.dc.html` the twelve artboards it describes.

Open the HTML in any browser. It renders standalone: the canvas runtime it was authored
with is not included, and does not need to be, because the artboards are plain HTML and
CSS and the font link and keyframes still apply from the body.

The five built-in palettes in `ui/theme/Palette.kt` began as sRGB conversions of the
concept's oklch values; `ui/theme/OmarchyThemes.kt` adds twenty-two more, generated from
the themes Omarchy ships. Two rules worth repeating: amber means "this lives somewhere
else" and nothing else, red is only ever destructive, and there is never a third accent
hue.

Where the build departs from the document, and why:

| Concept | Here | Why |
| --- | --- | --- |
| Scrubber with playhead | Both, chosen per source | Seekable sources get the scrubber; live streams get `--- STREAMING ---`, matching `renderSeekBar()` in the cliamp TUI. |
| Striped art placeholder | Real art where it exists, plate as fallback | Local tags, provider `getCoverArt`, then a station's og:image. None of that is invented art; the plate still covers the misses. |
| Amber means remote host | Also means reconnecting | Red is reserved for destructive actions, so amber was the only honest choice left. |
| Identity strip on the player | Removed | Its format readout moved into the meta line under the title. |
| Three descending bars as the mark | The real eight-bar logo | The concept's mark was a stand-in for exactly this. |
| Remote hosts screen | cliamp radio statistics | Same amber semantics, pointed at the thing that actually is remote. |
| One monospace face everywhere | Poppins for text, JetBrains Mono for numeric readouts | One face could not carry both an editorial title and a clock that ticks without shifting. The widget falls back to system mono either way: Android widgets cannot load `res/font`. |
| Two palettes | Twenty-seven | Every Omarchy theme, generated into the same 32-role contract. |
| Animated brick meter | Static rule in widget and notification | Neither surface can animate. |

## Licence

Proprietary. All rights reserved, see `../LICENSE`. No permission is granted to use,
copy, modify or redistribute this code.

Bundled third-party components keep their own terms, listed in
`../THIRD-PARTY-NOTICES.md`. JetBrains Mono ships under the SIL Open Font License,
so `../licenses/JetBrainsMono-OFL.txt` has to travel with any build.
