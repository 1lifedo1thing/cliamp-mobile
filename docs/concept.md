# cliamp mobile — the concept

The design spec for `Cliamp Mobile.dc.html`, in the CSS the artboards are drawn
in. This is where the app came from, kept verbatim.

It is no longer what the app is. The shipped Android build has moved on — a
second type family, twenty-seven palettes, oxide rather than phosphor green as
the default — and [`design.md`](design.md) is that, read out of the code. Read
this one for the original intent and the reasoning behind the geometry; read
`design.md` to build something that matches what exists.

## Palettes

Two builds, same geometry. Only ground, ink, and accent flip.

### Dark (default)

| Role | Value |
|---|---|
| Canvas (behind phones) | `oklch(0.28 0.006 150)` |
| Screen ground | `oklch(0.155 0.008 150)` |
| Screen ground, scope/lockscreen | `oklch(0.13 0.008 150)` / `oklch(0.115 0.006 150)` |
| Panel / raised row | `oklch(0.185 0.009 150)` – `oklch(0.19 0.012 150)` |
| Key face | `oklch(0.215 0.010 150)`, border `oklch(0.32 0.012 150)` |
| Hairline | `oklch(0.225 0.01 150)` (rows), `oklch(0.26 0.01 150)` (regions) |
| Ink | `oklch(0.96 0.01 150)` |
| Ink, secondary | `oklch(0.72 0.012 150)` |
| Ink, tertiary / labels | `oklch(0.56–0.60 0.012 150)` |
| Accent (phosphor) | `oklch(0.84 0.17 148)` |
| Accent ink-on-accent | `oklch(0.19 0.04 148)` |
| Remote host (amber) | `oklch(0.80 0.13 78)` |
| Destructive | `oklch(0.55 0.16 27)` / text `oklch(0.70 0.16 27)` |
| Unlit meter cell | `oklch(0.265 0.022 150)` |

### Light

| Role | Value |
|---|---|
| Screen ground | `oklch(0.975 0.006 150)` |
| Panel | `oklch(0.945 0.008 150)` |
| Hairline | `oklch(0.885 0.010 150)` |
| Ink | `oklch(0.24 0.012 150)` |
| Ink, secondary | `oklch(0.46 0.012 150)` |
| Ink, tertiary | `oklch(0.60 0.012 150)` |
| Accent | `oklch(0.55 0.15 148)` (darkened for 4.5:1 on paper) |
| Accent wash | `oklch(0.92 0.05 148)` |
| Remote host (amber) | `oklch(0.58 0.13 62)` |
| Unlit meter cell | `oklch(0.885 0.020 150)` |

Rules: hue stays in the 148–150 family for every neutral, so greys read faintly green.
Amber is reserved for "this lives on a remote host" and nothing else. Red only for
destructive actions (drop from queue, purge cache). Never introduce a third accent hue.

## Type

JetBrains Mono, weights 400 / 500 / 700. No second family anywhere.

| Use | Size / weight |
|---|---|
| Clock (lockscreen) | 76 / 500, `-0.04em` |
| Screen title | 24 / 700, `-0.02em` |
| Track title, now playing | 28 / 700, `-0.015em` |
| Track title, compact | 16–19 / 700 |
| Row primary | 14–15 / 400–500 |
| Row secondary, meta | 11–12 / 400 |
| Section label | 11 / 400, `letter-spacing 0.14em`, uppercase, tertiary ink |
| Chip / key cap | 11–12 / 400, `letter-spacing 0.06–0.1em`, uppercase |
| Tab label | 10 / 400, `letter-spacing 0.1em` |

Numbers (durations, sizes, rates) are always tabular by virtue of the monospace face —
never re-align them with letter-spacing hacks.

## Frame & layout

- Phone: 390 × 844, radius 46, `overflow: hidden`, one `flex-direction: column` stack.
- Three zones: fixed header (status bar + title/filters), `flex: 1` scroll region, fixed tab bar.
- Any `flex: 1` column that holds an aspect-ratio child needs `min-height: 0`, and the child
  needs `flex: 0 1 auto; max-height: …` — otherwise it pushes the tab bar out of the frame.
- Screen gutter: 22px. Row vertical padding: 11–14px. Region gap: 24–30px.
- Status bar: 52px tall, content bottom-aligned with 6px of padding.
- Tab bar: `13px 0 30px` (the 30 is the home indicator), 2px accent top border on the active
  item, `margin-top: -1px` so it sits on the divider.
- Lists are hairline-separated rows, never floating cards. Cards appear only for host
  entries, transfers, and the lockscreen widget — radius 10–16, 1px border, panel fill.

## Controls

**Transport keys.** Dark: `background: key face`, `box-shadow: inset 0 -3px 0 <darker>` — a
recessed bevel. Light: `background: ground`, `box-shadow: 0 3px 0 <hairline>` — a drop shelf.
Both read as physical; the primary key is filled (accent in dark, ink in light) and 1.7× wider.
Height 56–64px; nothing interactive below 44px.

**Chips / filters.** 11px uppercase, radius 5–6, `padding: 7–8px 11–12px`. Selected = filled
accent (dark) or filled ink (light); unselected = ground + 1px border.

**Toggles.** 44 × 26 pill, 20px knob, accent when on, `oklch(0.28 0.01 150)` when off.

**Sliders.** Square-ish 18–22px handle with the same bevel/shelf as the keys — never a circle.
Track is a 6px hairline bar, filled portion accent.

**Scrubber.** Full-width, 24px tall hit area, 4px track, 3px vertical playhead in ink.
Label under it states the gesture ("drag anywhere · hold to scrub fine").

## The brick meter

The signature element. A column is three stacked layers, all anchored to the bottom so
brick phase never shifts as the level animates:

1. Unlit grid — `position: absolute; inset: 0` with
   `repeating-linear-gradient(to top, <unlit> 0 Npx, transparent Npx (N+gap)px)`.
2. Lit grid — same gradient in accent, `bottom: 0`, animated `height` via `@keyframes brick`.
3. Peak cap — one brick-tall bar in ink (light) or white (dark), animated `bottom` via
   `@keyframes peak` with the same duration but `+0.16s` delay, so it lags and floats above.

```css
@keyframes brick { 0% { height: 12%; }  45% { height: 68%; }  100% { height: 96%; } }
@keyframes peak  { 0% { bottom: 14%; }  45% { bottom: 72%; }  100% { bottom: 97%; } }
```

Sizes by context: now playing 24 cols / 4px brick / 3px gap / 66px tall; hero scope 32 cols /
6px / 4px / ~200px with a frequency ruler (32 → 20k) and a dB readout; lockscreen 28 cols /
3px / 3px / 40px. Per-column variety comes from staggered `animation-duration` (0.85–1.5s)
and `animation-delay` (0–0.42s) plus a starting `height` — never from randomised colors.

## Icons

Hand-drawn geometry only: rectangles, triangles, straight paths. 13–17px, `stroke-width 1.7`
for outline icons, `fill: currentColor` for solid ones. The cliamp mark is three descending
bars (24×24 viewBox, rows at y=4/10/16 with decreasing widths) — a stand-in for a horizontal
level meter and a text file at the same time. No rounded caps, no gradients, no emoji.

## Imagery

Album art is never invented. It's a striped placeholder —
`repeating-linear-gradient(135deg, <panel> 0 5–6px, <ground> 5–6px 10–12px)`, 1px border,
radius 3–5, with a monospace caption in tertiary ink saying what belongs there
(`[ album art 1400×1400 ]`). Format badges (FLAC) sit top-right in a hairline box.

## Copy

Lowercase and terse, the way a config file talks: `add host`, `re-index`, `purge cache`,
`off — cached tracks only when away from wifi`. Paths and hosts appear verbatim
(`dhh@nas.local:22`, `/srv/music`, `ed25519 SHA256:t9Qv…`). The command bar uses a leading
`:` and a blinking block cursor. Never sentence-case a UI label, never add an exclamation mark.

## Adding a screen

1. Copy an existing phone frame (header / scroll / tab bar), keep the 22px gutter.
2. Pick the palette by which section you're in — dark screens are turn 2, light are turn 3.
3. Content is hairline rows first; reach for a card only if the thing is an object with state
   (a host, a transfer).
4. Give the tab bar the right active item; keep all four items even if unused.
5. Check the frame: children must sum to ≤ 844px. If they don't, tighten row padding and
   section-label spacing — never let content clip mid-glyph at the scroll edge.
