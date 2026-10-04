---
name: gpui-kit
description: >
  GPUI Kit rules for the kleeamp desktop app. Use when writing or reviewing
  gpui-kit views, entities, actions, focus, lists, tables, or async UI work.
  Trigger examples: "gpui-kit view", "kit pane", "keybinding", "virtual list",
  "cx.notify", "entity", "Root", "Button", "Table", "blank window", "element
  id". Not for audio, providers, or queue policy — those stay out of the UI
  crate. Not for Compose or the raw gpui-ce fork.
user-invocable: true
license: Apache-2.0
compatibility: Desktop app on gpui-kit 0.6 (Longbridge). Do not apply to Compose.
---

# GPUI Kit

One dependency builds the desktop UI: `gpui-kit = "0.6"`. It re-exports the
matching GPUI snapshot (`gpui-pre`), `gpui-base`, the styled `gpui-component`
library, and default icon assets. Application code imports GPUI through
`use gpui_kit::*;` and components through `gpui_kit::component`. Never add
`gpui`, `gpui-pre`, or `gpui-component` as their own dependencies.

The site may be ahead of 0.6. When a doc sample fails to compile, the pinned
crate wins. Stay on 0.6 until `desktop/` changes the version on purpose.

Official map, in this order:

1. <https://gpui-kit.com/docs/getting-started>
2. <https://gpui-kit.com/docs/entity>, <https://gpui-kit.com/docs/context>, <https://gpui-kit.com/docs/render>
3. <https://gpui-kit.com/docs/element_id>, <https://gpui-kit.com/docs/focus>, <https://gpui-kit.com/docs/action>, <https://gpui-kit.com/docs/keybinding>
4. <https://gpui-kit.com/docs/task>, <https://gpui-kit.com/docs/window>
5. Component pages under <https://gpui-kit.com/component> and base pages under <https://gpui-kit.com/base>

Before the first view in a session, read [references/model.md](references/model.md).
Before choosing a control, read [references/components.md](references/components.md).
Zed blog posts and the gpui-ce fork describe a different crate graph. Use the
pages above.

Only `crates/app` may import this crate. `core`, `playback`, and `providers`
must compile with gpui-kit deleted. Crate rules live in the `rust-desktop` skill.

## Startup

```rust
use gpui_kit::*;

fn main() {
    application()
        .with_assets(assets::Assets)
        .run(|cx| {
            init(cx); // once, before any window or component
            cx.bind_keys([
                KeyBinding::new("space", TogglePlay, Some("Player")),
                KeyBinding::new("ctrl-k", OpenSearch, Some("Workspace")),
            ]);
            open_window(WindowOptions::default(), cx, |_, cx| {
                cx.new(|cx| Workspace::new(cx))
            })
            .expect("no GPU device for kleeamp window");
        });
}
```

`application()` creates the desktop app. `init(cx)` initializes the enabled
layers, including component themes. `open_window` wraps the content view in a
`Root` that owns dialogs, sheets, and notifications. Return the content view
from the closure. A second `Root` fights the one the kit already installed.

Call `cx.bind_keys` during this setup, before `cx.set_menus` if the app has a
native menu. A menu snapshots the keymap at the moment `set_menus` runs.

## Model

```
Workspace (Entity, Render)
  ├─ Session (Entity, no Render)     queue, accounts, load state
  ├─ Library / Stations / Podcasts   one Entity per pane
  └─ element tree                    rebuilt every frame
       └─ RenderOnce values          rows, plates, buttons
```

1. A view is an `Entity` whose type implements `Render`. `render` returns elements and returns. It does no HTTP, SQL, decode, or `block_on`.
2. Mutate, then `cx.notify()` on that entity's context. A field write does not repaint.
3. `read` borrows. The borrow ends when the statement ends. Copy out what a later update needs.
4. Clicks go through `cx.listener` or a component `on_click`. Commands that also have a key go through an action.
5. IO uses `cx.spawn` / `cx.spawn_in`. The task sends a result back with `WeakEntity::update`. It does not touch widgets.

One entity per pane (`Session`, `LibraryView`, `Player`, `Settings`), not per row. Track tables use the kit virtualized table or virtual list. A hand-rolled `uniform_list` is the fallback when a component cannot take palette roles.

Retained control state (`InputState`, focus handles, table state) is created in `new` and stored on the entity. Building it inside `render` resets the control every frame.

`notify()` and `emit()` are different signals. `notify` invalidates renderers and observers. `emit` delivers a typed event to subscribers. A change that needs both calls both. Store every `Subscription` on the subscriber. A local subscription dropped at the end of `new` is cancelled.

## Element identity

An `ElementId` is a local key. GPUI joins it with keyed ancestors and the view's `EntityId` into a `GlobalElementId`. Hover, click, focus, and `with_element_state` hang off that path.

- Call `.id(...)` once. A later `.id(...)` on the same element replaces the key.
- Under one keyed parent, each repeated child needs its own id, taken from the domain id (`("row", track.id)`), never from the label and never from a loop index when the list can reorder.
- Unkeyed wrappers do not create a new namespace. Two `div().id("item")` under the same keyed ancestor collide.
- A shared id makes every row one element. Hover and click collapse onto the last row. `with_element_state` can panic because the same state is borrowed reentrantly.

Details and the row pattern: [references/model.md](references/model.md).

## Focus and actions

```rust
actions!(player, [TogglePlay, SeekForward]);
```

Retain a `FocusHandle` on the pane that owns the keys. Put it on the element with `.track_focus(&handle)`. A handle that is not tracked is not a keyboard target. Tab stops opt in with `.tab_stop(true)` when the handle is created.

Bind keys in app setup, with a context name (`"Player"`, `"Workspace"`). Give the matching pane `key_context`. Tab order is rail, center, player.

A toolbar button, a menu item, and a key that do the same thing dispatch one action (`window.dispatch_action`) or call one method. Dispatch is deferred to the focused path. The handler lives on that path.

`on_action` handlers take the action first and `window, cx` last.

## Async

```rust
let this = cx.weak_entity();
cx.spawn(async move |_, async_cx| {
    let page = load_page().await;
    this.update(async_cx, |pane, cx| {
        pane.page = page;
        cx.notify();
    })
    .ok();
})
.detach();
```

Hold the `Task` on the entity when closing the pane should cancel the work. `detach` when the work should finish anyway. A failed weak update after `await` means the view is gone.

`cx.spawn_in(window, ...)` plus `update_in` is the form that also needs the `Window` (focus a field when the result arrives).

GPUI's entity borrow is non-reentrant. `render` and `update` already hold it. `read` or `update` on that same entity from inside the borrow panics. Update a different entity, or finish this update and use the returned value.

Do not wrap the session in `std::sync::Mutex` and lock it from `render`. Render already runs inside GPUI's context. A second lock of a non-reentrant mutex never returns, the first frame never finishes, and Wayland never gets a surface. Background threads enter through `AsyncApp` / `WeakEntity::update` only.

`cx.notify()` runs on the entity's context inside that update. Calling it from a worker thread is a use of the UI runtime off the UI runtime.

## State the view may hold

| Kind | Where |
| --- | --- |
| Palette roles, selection, `Load<T>` | the pane entity |
| Queue, tracks, accounts | `core` types, owned by `Session` or `Player` |
| Spectrum, position | last event from `playback`, copied into `Player` |
| Secrets, sockets, SQL | never in a view |

`Load<T>` is `Idle | Loading | Ready(T) | Failed(UserError)`. A failed refresh keeps the last `Ready` page and paints one fault line. Amber is reconnecting. Red is destructive only.

## Styling

Kleeamp surfaces use palette roles (`accent`, `hairline`, `inkFaint`). Map a role onto a kit prop at the call site. Kit components keep the kit theme for stock controls (dialogs, inputs, menus). Forking `Theme::global_mut` to recolor the whole kit is how the two systems get welded together and then both look wrong.

Poppins for titles, rows, buttons. JetBrains Mono only for clocks, bitrate, sample rate, format, hosts. Gutter 22px. Rows and hairlines. A card is for an object with its own state (a configured host). Plates stand in for missing art. The role values live in `android/.../theme/KleeampPalette.kt`. The desktop port skill says how the window uses them.

## Tests

A pane test exists only when the interaction is the bug (`rust-desktop`). Headless checks use the `test-support` feature and `#[gpui_kit::test]`. See <https://gpui-kit.com/docs/test>. Query a repeated row with `window.within(("message", id)).find("archive")`, not a bare `find` that matches every row.

## Do not

- Block the UI thread (`block_on`, std filesystem, SSH, decode).
- Build a retained element per track. Virtualize.
- Call `cx.notify()` from a background thread.
- `read` / `update` an entity that is already in `render` or `update`.
- Lock a `Mutex` around app state from inside `render`.
- Give two live siblings the same element id.
- Create `InputState`, `FocusHandle`, or table state inside `render`.
- Store a strong `Entity` from child back to parent. Store `WeakEntity`.
- Override one GPUI package to a different version than the kit pins.
- Put `unwrap` in a click handler. Map the error into `Load::Failed`.
- Import gpui-kit from `core`, `playback`, or `providers`.
