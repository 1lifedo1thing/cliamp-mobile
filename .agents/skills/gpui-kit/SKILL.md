---
name: gpui-kit
description: >
  GPUI Kit rules for the kleeamp desktop app. Use when writing or reviewing
  gpui-kit views, entities, actions, focus, lists, tables, or async UI work.
  Trigger examples: "gpui-kit view", "kit pane", "keybinding", "virtual list",
  "cx.notify", "entity", "Root", "Button", "Table". Not for audio, providers,
  or queue policy — those stay out of the UI crate. Not for Compose or the
  raw gpui-ce fork.
user-invocable: true
license: Apache-2.0
compatibility: Desktop app on gpui-kit 0.6 (Longbridge). Do not apply to Compose.
---

# GPUI Kit

One dependency builds the desktop app: `gpui-kit = "0.6"`. It brings the
matching GPUI snapshot (`gpui-pre`), `gpui-base`, the styled `gpui-component`
library, and default icon assets. Application code imports GPUI through
`use gpui_kit::*;`, components through `gpui_kit::component`, and never
lists GPUI itself. Follow gpui-kit.com/docs (0.6.x) and docs.rs/gpui-kit,
not Zed blog posts or the gpui-ce fork docs.

Only `crates/app` may import this crate. `core`, `playback`, and
`providers` must compile with gpui-kit deleted.

## Startup

```rust
use gpui_kit::*;

fn main() {
    application()
        .with_assets(assets::Assets)
        .run(|cx| {
            init(cx); // once, before windows or components
            open_window(WindowOptions::default(), cx, |_, cx| cx.new(|_| Workspace::new(cx)))
                .expect("no GPU device for kleeamp window");
        });
}
```

`application()` creates the desktop app, `init(cx)` initializes the enabled
layers (themes included), `open_window` mounts the content view inside a
`Root` that owns overlays (dialogs, sheets, notifications). Return the
content view from the closure, not another `Root`.

## Data flow

1. A view is an `Entity` that implements `Render`.
2. `render` builds elements and returns. No HTTP, SQL, decode, or `block_on`.
3. Mutate, then `cx.notify()`. A field write does not repaint.
4. Clicks go through `cx.listener` or component `on_click`. Keys go through
   actions and `KeyBinding` (`ctrl-k` search, space play/pause).
5. IO goes through `cx.spawn`. The task sends a result back into the entity.
   It does not touch widgets.

One entity per pane (`Session`, `LibraryView`, `Player`, `Settings`), not
per row. Track tables use the kit virtualized `List`/`Table` (only the
visible range renders); a hand-rolled `uniform_list` is the fallback when a
component cannot take palette roles.

Mental model: `Entity<Model>` is retained state, `Entity<View>` renders it
into a fresh element tree each frame, `RenderOnce` values are reusable pieces
(buttons, rows, plates). Complex state gets a lasting owner, never a value
rebuilt inside `render`.

## Actions

```rust
actions!(player, [TogglePlay, SeekForward]);

fn register(cx: &mut App) {
    cx.bind_keys([
        KeyBinding::new("space", TogglePlay, Some("Player")),
        KeyBinding::new("ctrl-k", OpenSearch, Some("Workspace")),
    ]);
}
```

Bind in app setup, not inside `render`. Give each pane a `FocusHandle` and
`key_context`. Tab order is rail, center, player.

## State the view may hold

| Kind | Where |
| --- | --- |
| Palette roles, selection, `Load<T>` | the pane entity |
| Queue, tracks, accounts | `core` types, owned by `Session` or `Player` |
| Spectrum, position | last event from `playback`, copied into `Player` |
| Secrets, sockets, SQL | never |

`Load<T>` is `Idle | Loading | Ready(T) | Failed(UserError)`. A failed
refresh keeps the last `Ready` page and paints one fault line. Amber is
reconnecting. Red is destructive only.

## Styling

Components take palette roles (`accent`, `hairline`, `inkFaint`), never hex.
The kit theme drives kit components; kleeamp roles drive kleeamp surfaces —
map roles onto component props at the call site, do not fork the theme.
Poppins for titles, rows, buttons. JetBrains Mono only for clocks, bitrate,
sample rate, format, hosts. Gutter 22px. Rows and hairlines, not cards,
except an object with state (a host). Do not invent album art.

## Do not

- Block the UI thread.
- Build a retained element per track.
- Call `cx.notify()` from a background thread; hop back to the entity first.
- Override one GPUI package to a different version than the kit pins.
- Put `unwrap` in a click handler. Map the error into `Load::Failed`.
