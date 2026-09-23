# kleeamp architecture (target)

Same app, same behavior. **Flat vertical slices** — no `feature/` folder, no `kernel/` folder. Each product job is a top-level package under `stream.kleeamp.mobile`.

> Status after the refactor run (steps 00–20): **current = target.** The tree
> below is what is on disk. `ui/` and `data/` are gone; `feature/` and
> `kernel/` were never created.

```
stream.kleeamp.mobile
├── KleeampApp.kt              # composition root (stays at package root)
├── MainActivity.kt
├── KleeampRoot.kt             # nav shell
├── NavRoutes.kt
├── ViewModels.kt              # appViewModel()
│
├── play/                      # PlayFromList, QueuePolicy
├── playback/                  # Media3 session, connection, bus, resolver
├── model/                     # Station
├── db/                        # Room
├── prefs/                     # DataStore + Keystore
├── net/                       # OkHttp
├── art/                       # covers / placeholders
├── theme/                     # 32-role palettes
├── chrome/                    # generic keys, hairlines, plates
│
├── radio/                     # channels + directory
├── podcasts/
├── library/                   # local files + playlists
├── servers/                   # providers, wizard, SFTP clients
├── player/                    # now playing, mini, up next, scope, vis
├── search/
├── settings/
└── widget/
```

A slice owns its screen, ViewModel, and data. Shared engine stays in `playback` / `model` / `db` / `play`.

No Hilt. No new Gradle modules. No UX changes. No queue-rule changes.

New code goes in the named slice (`radio`, `podcasts`, …) or in `playback` / `model` / `theme`.

## Import rules

```
KleeampApp / MainActivity / KleeampRoot
    may see every slice (they are the shell)

theme, chrome
    → model, art
    → not radio/podcasts/library/servers/player
    → not Media3

widget
    → playback, model, theme, art

radio, podcasts, library, servers, player, settings
    → play, playback (as API), model, db, prefs, net, art, theme, chrome
    → not each other's internals

search
    → public query functions on the other slices

playback
    → model, net, prefs, play, servers.MediaProvider
    → not *Screen.kt

only playback/ and widget/ may import androidx.media3
only KleeampApp.kt and ViewModels.kt may import KleeampApp
```
