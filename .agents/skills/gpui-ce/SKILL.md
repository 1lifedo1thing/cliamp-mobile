---
name: gpui-ce
description: >
  GPUI-CE rules for a standalone desktop app. Use when writing or reviewing
  gpui-ce views, entities, actions, focus, lists, or async UI work. Trigger
  examples: "gpui view", "gpui-ce pane", "keybinding", "uniform_list",
  "cx.notify", "entity". Not for audio, providers, or queue policy — those
  stay out of the UI crate. Not for Zed gpui, gpui-unofficial, or
  longbridge gpui-component.
user-invocable: true
license: Apache-2.0
compatibility: Desktop app on gpui-ce 0.2.2. Do not apply to Zed or Compose.
---

# GPUI-CE

The UI crate depends on `gpui-ce = "=0.2.2"` only. Never add `gpui`,
`gpui-unofficial`, `gpui-platform-gpui-unofficial`, or a git dep on
`zed-industries/zed`. CE is mostly compatible with Zed's gpui and is
diverging; follow docs.rs/gpui-ce/0.2.2 and gpui-ce.github.io, not Zed
posts. If a snippet imports `gpui::`, rewrite it to `gpui_ce` / the name
the crate actually exports. Check `cargo metadata` before inventing a path.

Only `crates/app` may import this crate. `core`, `playback`, and
`providers` must compile with gpui-ce deleted.

## Data flow

1. A view is an `Entity` that implements `Render`.
2. `render` builds elements and returns. No HTTP, SQL, decode, or `block_on`.
3. Mutate, then `cx.notify()`. A field write does not repaint.
4. Clicks use `cx.listener`. Keys use an action, `on_action`, and `KeyBinding`.
5. IO goes through `cx.spawn`. The task sends a result back into the entity. It does not touch widgets.

One entity per pane (`Session`, `LibraryView`, `Player`, `Settings`), not per row. Track tables use `uniform_list`.

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

Bind in app setup, not inside `render`. Give each pane a `FocusHandle` and `key_context`. Tab order is rail, center, player.

## State the view may hold

| Kind | Where |
| --- | --- |
| Palette roles, selection, `Load<T>` | the pane entity |
| Queue, tracks, accounts | `core` types, owned by `Session` or `Player` |
| Spectrum, position | last event from `playback`, copied into `Player` |
| Secrets, sockets, SQL | never |

`Load<T>` is `Idle | Loading | Ready(T) | Failed(UserError)`. A failed refresh keeps the last `Ready` page and paints one fault line. Amber is reconnecting. Red is destructive only.

## Styling

Components take palette roles (`accent`, `hairline`, `inkFaint`), never hex. Poppins for titles, rows, buttons. JetBrains Mono only for clocks, bitrate, sample rate, format, hosts. Gutter 22px. Rows and hairlines, not cards, except an object with state (a host). Do not invent album art.

## Do not

- Block the UI thread.
- Build a retained element per track.
- Call `cx.notify()` from a background thread; hop back to the entity first.
- Copy a longbridge or Zed component that pulls `gpui` 0.1/0.2 from Zed.
- Put `unwrap` in a click handler. Map the error into `Load::Failed`.
