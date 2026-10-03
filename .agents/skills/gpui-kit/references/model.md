# GPUI Kit model

Read this before writing a view. Method-level API lives on
<https://gpui-kit.com/docs>. This file is the ownership and failure model
for kleeamp.

## Layers

| Crate | What it is |
| --- | --- |
| `gpui` (`gpui-pre`) | Entities, windows, elements, layout, paint, actions |
| `gpui-base` | Unstyled behavior: input engine, virtual list, overlays, dock, tokens |
| `gpui-component` | Styled controls on top of base |
| `gpui-kit` | Facade. `application()`, `init()`, `actions!`, re-exports |

`gpui_kit::init` initializes base. Do not also call `gpui_base::init`.

Base owns behavior (focus trap, virtual range, text editing). The app owns
brand color, type, density, and how a row looks. Styled components are for
stock controls. Kleeamp chrome (rail, hairline row, plate, transport key) is
app-drawn and reads palette roles.

## Entity

`Entity<T>` is a handle. Cloning the handle does not clone `T`.

| Operation | Use |
| --- | --- |
| `cx.new(\|cx\| T)` | create. The closure receives `Context<T>` |
| `entity.read(cx)` | short shared borrow. Do not store the `&T` |
| `entity.update(cx, \|t, cx\| …)` | mutate. `cx` here is `Context<T>` for that entity |
| `entity.downgrade()` | `WeakEntity<T>` for back-refs and tasks |
| `cx.observe(&entity, …)` | run when that entity `notify`s. Keep the `Subscription` |
| `cx.subscribe(&entity, …)` | typed event. `notify` does not emit. `emit` does not notify |

`T` implements `Render` only when the entity is mounted as a view. A session
model has no `Render`. Panes observe it and call `cx.notify()` on themselves.

Parent owns child with `Entity`. Child points at parent with `WeakEntity`.
`upgrade()` returns `None` after the parent is gone. `cx.listener` and
`cx.observe` already use weak subscriber handles. `cx.processor` captures a
strong handle. Do not store a `processor` closure back on the same view.

Dropping the `Subscription` cancels the callback. `Subscription::detach`
keeps it until the observed entity dies. Repeated detach onto a long-lived
entity piles callbacks up.

Release is deferred to GPUI's effect cycle. Do not depend on `Drop` running
inside the `drop(handle)` statement. `cx.on_release` / `cx.observe_release`
are for integration cleanup.

## Render

`Render::render(&mut self, window, cx) -> impl IntoElement` builds this
frame's tree. The entity survives. The `div` values do not.

`RenderOnce` is a value component. The caller passes state and handlers in.
Use it for a row, a plate, a key. Use `Render` when the type owns
subscriptions, tasks, focus, or child entities.

A parent renders a child view by holding `Entity<Child>` and placing it in
the tree (`.child(self.library.clone())`). The child's `EntityId` is part of
its element path.

`window.use_keyed_state(key, cx, init)` keeps a small `Entity` for as long as
that key is touched on consecutive frames. Hiding the row for a rendered
frame drops it. Anything that must survive hiding lives on a real model
entity. `use_state` keys by call-site. Inside a loop that shares one keyed
ancestor, use `use_keyed_state` with the item id.

## Context kinds

| Type | Scope |
| --- | --- |
| `App` | process. Globals, new entities, open windows, app actions |
| `Context<T>` | the entity currently updated. `notify`, subscribe, spawn |
| `Window` | this OS window. Focus, bounds, paint, action dispatch |
| `AsyncApp` | re-enter `App` after `await` |
| `AsyncWindowContext` | re-enter one window after `await` |

Runtime parameters go last: `window, cx`. Action handlers take `&Action`
first, then `window, cx`.

There is one GPUI context. Name it `cx`. Do not also name an app object `cx`.

## Element identity

```rust
div().id("tracks").children(tracks.iter().map(|track| {
    div()
        .id(("track", track.id.clone()))
        .child(track.title.clone())
        .child(Button::new("play").label("Play"))
}))
```

`"play"` is legal on every row because the row id differs. Two
`Button::new("play")` in the same row collide. `"tracks" → ("track", id) → "play"`
is the path.

Index keys stick to a position. After an insert, focus and hover belong to
a different track. Use an index only when the position itself is the identity.

Changing an id, or a keyed ancestor's id, resets element state. Adding `.id`
does not skip `render`. Cache (`cached(style)`) is a separate mechanism and
needs a stable child entity plus a definite outer size. See
<https://gpui-kit.com/docs/view-cache>.

Custom paint that stores frame state uses `window.with_element_state` during
prepaint, with an element that returns an id. Ordinary views keep that state
on an entity instead.

## Focus, actions, events

```rust
actions!(workspace, [OpenSearch, CloseOverlay]);

struct Workspace {
    focus: FocusHandle,
}

impl Workspace {
    fn new(cx: &mut Context<Self>) -> Self {
        Self { focus: cx.focus_handle().tab_stop(true) }
    }
}
```

Track it: `.track_focus(&self.focus).key_context("Workspace")`.

```rust
cx.bind_keys([
    KeyBinding::new("ctrl-k", OpenSearch, Some("Workspace")),
]);
```

Bind during `application().run`, before menus. Context string matches
`key_context`.

Pointer-only behavior stays in `on_click`. A command with a key, a menu row,
and a button is one action. `window.dispatch_action(action.boxed_clone(), cx)`
defers to the focused dispatch path. Put `on_action` on an element on that
path (the focused pane, or a common owner).

`cx.emit` requires `EventEmitter<E>`. Subscribers see the event after the
current update. They must not `update` an entity already borrowed by the
callback chain. GPUI delivers observe/subscribe after the update borrow ends.
Code inside the `update` closure must not assume the observer has already run.

## Tasks

UI thread work ends when `render` or the click handler returns. Anything
slower is a task.

```rust
cx.spawn_in(window, async move |this, cx| {
    let rows = fetch_rows().await;
    this.update_in(cx, |pane, window, cx| {
        pane.rows = rows;
        pane.focus.focus(window, cx);
        cx.notify();
    })?;
    anyhow::Ok(())
})
.detach();
```

`Context::spawn` passes a `WeakEntity<Self>` as the first async argument.
Do not capture `&mut self` or `&App` across `.await`. Copy the handle, the
ids, and the request before the first await.

Cancellation: drop the `Task` to cancel. After await, `update` returns `Err`
if the entity is gone. Treat that as cancel. Log other errors with `tracing`
and store `Load::Failed`. No `unwrap` on the worker, and no panic.

`block_on` on the UI thread freezes the window, including the frame that was
supposed to show a spinner.

## Blank window

The process can be alive with no window. Check these in order:

1. `init(cx)` did not run before `open_window` or the first component.
2. The window closure returned another `Root`, or returned a view that never
   implements `Render`.
3. `render` locks a `std::sync::Mutex` that the same call stack locks again.
   GPUI's context is already held. A non-reentrant mutex here never returns,
   so the first frame never commits and Wayland has no surface.
4. `render` calls `entity.update` on itself (directly, or through a helper
   that takes `&App` and updates the pane being rendered). That panics.
5. `expect` on `open_window` fired. The message is the GPU failure. Do not
   replace it with `unwrap`.

A click that does nothing visible is usually a missing `cx.notify()` on the
entity that renders the changed field, or a dropped `Subscription` that was
supposed to forward a model `notify` to the pane.

## Lists

Thousands of tracks: the delegate renders the visible range. The model owns
the `Vec`. Selection stores domain ids, so a filter does not move the
selection onto a different row.

Kit table: `gpui_kit::component::table::{DataTable, TableState, TableDelegate}`.
Create `TableState` once in `new`. Docs:
<https://gpui-kit.com/component/data-table>.

Variable-height rows use base `VirtualList`. Confirm the module path on
<https://gpui-kit.com/base> against gpui-kit 0.6 before importing. One scroll
handle is one viewport. Sharing it between nested scroll areas mixes offsets.

## Dialogs

`Root` hosts them. From a click:

```rust
window.open_dialog(cx, |dialog, _, _| {
    dialog.title("Remove account").child("This drops the saved host.")
});
```

`WindowExt` comes from `gpui_kit::component`. The content view is not a
second `Root`. Escape and focus return are the kit's job. Restoring focus
on dismiss is the pane's job when it moved focus to open the overlay.

## What a frame is allowed to allocate

`render` runs on the UI thread for every invalidation. Cloning a `String`
into a `SharedString` for a visible row is normal. Cloning the whole library
to sort it inside `render` is not. Sort when the data arrives, store the
order on the model, and render the visible slice.
