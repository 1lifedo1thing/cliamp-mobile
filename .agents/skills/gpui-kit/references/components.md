# Which control

Confirm the builder methods on the linked page. gpui-kit 0.6 is the crate
that must compile. Pages move. If the path 404s, start at
<https://gpui-kit.com/component> or <https://gpui-kit.com/base> and follow
the sidebar. Imports below are the ones the 0.6 docs publish.

Kleeamp paints its own rail, list rows, plates, and transport keys. Use a
kit control when the behavior is the hard part (text editing, focus trap,
virtualization, menu placement).

## App shell

| Need | Use | Notes |
| --- | --- | --- |
| Process + window | `gpui_kit::application`, `init`, `open_window` | <https://gpui-kit.com/docs/getting-started> |
| Overlays | the `Root` that `open_window` installs | Return the content entity |
| Dialog | `window.open_dialog` via `gpui_kit::component::WindowExt` | <https://gpui-kit.com/docs/window> |
| Alert / destructive confirm | kit `AlertDialog` | <https://gpui-kit.com/component> |
| Toast | kit notification host on `Root` | One fault line in the pane is enough for a failed refresh |

## Input

Create state in `new`. Pass `&Entity<…>` into the element each frame.

| Need | Import |
| --- | --- |
| Single line | `gpui_kit::component::input::{Input, InputState, InputEvent}` |
| Multiline notes (private key, folder list) | `Textarea` / `TextareaState` |
| Checkbox, switch | `component::checkbox::Checkbox`, `component::switch::Switch` |
| A few exclusive choices | `component::radio::RadioGroup` |
| Labeled group | `component::form::{Form, Field}` |
| Slider (buffer seconds) | kit `Slider`. Confirm on the component index |

`InputState::new(window, cx)` needs the window, so `Workspace::new` takes
`window: &mut Window` when it builds fields. Subscribe with
`cx.subscribe_in(&state, window, …)` and match `InputEvent::Change`.
The settings recipe on <https://gpui-kit.com/docs/getting-started> is the
pattern.

Password fields stay `secret` in the provider spec. Do not log the value.
Do not put the value in a `SharedString` that a debug view prints.

## Commands

| Need | Use |
| --- | --- |
| Click | `Button::new(id).label(…).on_click(cx.listener(…))` |
| Variants | `.primary()`, `.danger()`, `.ghost()`, `.small()` from `ButtonVariants` |
| Several toggles | `ButtonGroup` |
| Same command as a key | `window.dispatch_action` |
| Icon | `gpui_kit::component::IconName` when a kit icon matches. Kleeamp tab marks are app-drawn |

```rust
use gpui_kit::component::button::{Button, ButtonVariants};
```

<https://gpui-kit.com/component/button>

`Button::new` takes an `ElementId`, then `.label`. The id is not the label.

## Data

| Shape | Control |
| --- | --- |
| Same fields every row, thousands of rows | `DataTable` + `TableDelegate` + `TableState` |
| Heterogeneous rows, variable height | base `VirtualList` |
| Real parent/child (provider folder tree) | base `Tree` / `TreeState` |
| Short settings page | a scrolling column. No virtualization under a few dozen rows |

Table import:

```rust
use gpui_kit::component::table::{
    Column, ColumnFixed, ColumnSort, DataTable, TableDelegate, TableEvent, TableState,
};
```

<https://gpui-kit.com/component/data-table>

Selection lives in the delegate or the pane as domain ids. Keyboard navigation
stays with the table. Do not add a second arrow-key handler that fights it.

## Menus and popovers

Right-click and the row ⋮ use the kit context menu / dropdown. Placement,
flip, and dismiss-on-outside are base behavior. Building a `div` popup by
hand reimplements hit testing.

A menu action calls the same method as the command. It does not contain a
second copy of the mutation.

## Layout pieces the app draws

These stay custom, because the kit theme is a different visual language:

| Surface | Draw with | Rule |
| --- | --- | --- |
| Shell: sidebar / rail / bottom tabs | `div` + palette | Three-pane shell when wide; rail or tabs below |
| Screen title row | `div` | Back, title, search, settings are separate hit targets |
| List row | `RenderOnce` | Hairline, 22px gutter, optional 2px accent rail on the playing row |
| Missing art | plate | Flat fill, hairline border. A letter only on playlist covers |
| Transport | app keys | Visual press. Haptics have no desktop device |
| Section label | `div` | 3×11 accent bar, tracked uppercase, tertiary ink |

Colors are role fields on the palette struct (`accent`, `ink`, `hairline`,
`ground`, `destructive`). Pass `cx` only to read theme when styling a kit
control (`cx.theme().background`). Kleeamp surfaces read the app palette
entity, not `cx.theme()`.

## Accessibility

Kit controls ship AccessKit roles. A custom row that clicks needs an id and
a role, plus a name that is the track title. Color is not the only playing
indicator. The accent rail is extra. The row name or a text marker still
says which row is current.

Test queries: <https://gpui-kit.com/docs/test>. `window.within(row_id).find(local_id)`.

## Fonts

Load Poppins and JetBrains Mono through the app asset source. Kit font docs:
<https://gpui-kit.com/docs/fonts>. Mono is for clocks, bitrate, sample rate,
format, and hosts. Everything else is Poppins. Sizes are the `KleeampType`
scale in `android/.../theme/Type.kt`. They do not grow with the window.
