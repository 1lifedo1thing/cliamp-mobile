# kleeamp — design system

Terminal soul, native body. Hairline rules instead of cards, one accent hue at a
time, and controls with real mechanical travel.

This is read out of the shipped Android build, not out of the concept. Where the
two disagree the code wins, and it disagrees in three places that matter: there
are two type families now, not one; there are twenty-seven palettes, not two;
and the default is oxide red rather than phosphor green. The original spec is
kept beside this as [`concept.md`](concept.md) for the reasoning behind the
geometry.

Anyone building a second client should be able to work from this file alone.
Source of record is `android/app/src/main/java/stream/kleeamp/mobile/ui/theme/`.

## Palette is a contract, not a colour scheme

The app never names a colour. It names a **role**, and a palette fills all of
them — 32 colour roles plus a `dark` flag, in `KleeampPalette`:

| Group | Roles |
| --- | --- |
| Grounds | `canvas` `ground` `groundScope` `groundLock` `panel` `panelRaised` |
| Lines | `hairline` `hairlineRegion` `frameBorder` |
| Ink | `ink` `inkBright` `inkSecondary` `inkTertiary` `inkFaint` |
| Accent | `accent` `accentBright` `accentBevel` `onAccent` `accentWash` |
| Semantic | `amber` `destructive` `destructiveInk` |
| Controls | `keyFace` `keyBorder` `keyBevel` `chipBorder` `track` `unlit` `peak` |
| Placeholder art | `artA` `artB` `artBorder` |

Filling all thirty-two is what makes a theme swap total rather than cosmetic. A
theme that only supplies a ground and an accent leaves bevels and meter cells
sitting on top of it as grey overlay, which is exactly the failure the
`keyBevel` / `unlit` / `chipBorder` roles exist to prevent.

Five palettes are hand-built:

| Key | Mode | Ground | Accent | Notes |
| --- | --- | --- | --- | --- |
| `oxide` | dark | `#120A08` | `#D15D4D` | The default. Rusted iron; grounds carry twice the neutral chroma at the red hue, so panels read as warm metal. |
| `oxide-light` | light | `#F8E4D4` | `#7F2117` | Oxide reversed — the cream that is ink there is the page here. |
| `dark` | dark | `#0A0D0A` | `#73E889` | The original phosphor green. Neutrals sit in the 148–150 hue family, so greys read faintly green. |
| `light` | light | `#F4F8F5` | `#007C2F` | Accent darkened for 4.5:1 on paper. |
| `amber` | dark | `#100B07` | `#F9AA60` | Dark's own lightness and chroma rotated to the accent hue. |

`system` resolves to `oxide` or `oxide-light` by device mode. Twenty-two more
come from `OmarchyThemes.kt`, generated from the `colors.toml` of every theme
Omarchy ships — catppuccin, everforest, gruvbox, kanagawa, nord, rose-pine,
tokyo-night, vantablack and the rest. That file is generated; regenerate it
rather than hand-editing.

A `colors.toml` gives grounds, inks and hues but no hairlines, bevels or meter
cells, so those are derived by blending ground toward ink at fixed ratios. Two
values are computed rather than read: `onAccent` is picked from the accent's own
luminance, because a pale accent needs dark text and a dark one needs light and
deciding by theme mode gets that wrong about half the time; `unlit` is the
ground blended slightly toward the accent, so the brick grid belongs to the
theme instead of sitting on it.

**Rules that survive every palette.** Amber means "this lives somewhere else" —
a remote host, a reconnecting stream — and nothing else. Red is only ever
destructive. There is never a third accent hue. An unknown theme key falls back
rather than throwing, so a theme removed from the machine cannot brick the app.

## Type

Two families, each with a job. This is the largest departure from the concept,
which specified one monospace face everywhere.

**Poppins** is the editorial face and the default: titles, artists, albums,
playlists, buttons, tabs, settings. **JetBrains Mono** is the numeric face, used
only for readouts that tick — elapsed and remaining clocks, buffered seconds,
bitrate, sample rate. It is there for tabular figures: a changing seconds column
must not shift the minutes beside it. Do not reach for it as a stylistic choice.

| Style | Size / weight | Tracking | Family |
| --- | --- | --- | --- |
| `trackTitle` | 28 Bold | −0.015em | Poppins |
| `screenTitle` | 24 Bold | −0.02em | Poppins |
| `trackTitleCompact` | 19 Bold | −0.012em | Poppins |
| `trackTitleSmall` | 16 Bold | −0.01em | Poppins |
| `rowPrimary` / `rowPrimaryMedium` | 15 Regular / Medium | 0 | Poppins |
| `body` | 13 Regular | 0 | Poppins |
| `rowSecondary` | 12 Regular | 0 | Poppins |
| `meta` | 11 Regular | 0 | Poppins |
| `sectionLabel` | 11 Medium | 0.14em | Poppins |
| `chip` | 11 Medium | 0.08em | Poppins |
| `nowPlayingLabel` | 11 Medium | 0.16em | Poppins |
| `tabLabel` | 10 Medium | 0.10em | Poppins |
| `time` | 13 Medium | 0 | JetBrains Mono |
| `timeSmall` / `datum` | 11 Regular | 0 | JetBrains Mono |

Line height is a multiplier of size, tightening as text gets larger: 1.6 for
body, 1.3–1.35 for rows, 1.14–1.2 for titles, 1.1 for clocks. Chrome — chips,
tabs, section labels, settings rows — sits at Medium so it reads a touch heavier
than the data around it.

## Layout

- **Gutter is 22dp**, horizontally, everywhere. Nothing is flush to the edge.
- **Rows, not cards.** `ListRow` is 12dp of vertical padding, a 12dp gap after
  any leading element, and 3dp between its two text lines, separated by 1dp
  hairlines. A card is only for an object with state — a host, a playlist tile.
- **Section labels** get 10dp vertical padding and take the whole width.
- **Two divider weights.** `hairline` between rows, `hairlineRegion` between
  regions; `HairlineDivider(region = true)` picks the second.
- **Tab bar**: 13dp above, 30dp below (or the gesture inset plus 8, whichever is
  larger), 17dp icons, 6dp between icon and label, and a 2dp accent rule drawn
  along the top edge of the active item.
- **Landscape swaps the bar for a 79dp rail** on the trailing side, so a
  horizontal frame keeps its full height for content.

## Controls

**`MechKey` is the signature control.** 64dp tall, 11dp radius, 3dp of travel,
collapsing on press with a stiff spring (1600) and a `VirtualKey` haptic. It is
built twice so both modes feel like the same physical switch:

- Dark: a **recessed bevel** — `keyBevel` welded to the bottom inside edge.
- Light: a **drop shelf** — the face sits 3dp above a `hairline` slab, and the
  component reserves `height + travel` to leave room for it.

Filled keys take `accent` over `accentBevel` in dark, `ink` over
`hairlineRegion` in light. Disabled is 38% alpha, never a colour change.

| Control | Geometry |
| --- | --- |
| Chip | 5dp radius, `chipBorder` when unselected, filled when selected |
| Icon toggle | 9dp radius, 15dp icon, 55% alpha on press |
| Switch | 44 × 26 pill, 20dp knob, accent when on |
| Slider handle | Square, 4dp radius, 1dp `keyBorder` — never a circle |
| Grid/list toggle | 34dp square, 6dp radius, 16dp icon |

Haptics run through `LocalHapticsEnabled`, so one setting silences every
mechanical control at once. Nothing interactive is smaller than 44dp.

## The brick meter

Three layers, all anchored to the bottom so brick phase never shifts as the
level animates: an unlit grid in `unlit`, a lit grid in `accent` grown from the
bottom, and a one-brick peak cap in `peak` that lags behind and floats above.

| Preset | Columns | Brick | Gap | Height |
| --- | --- | --- | --- | --- |
| `NowPlaying` | 24 | 4dp | 3dp | 66dp |
| `Scope` | 32 | 6dp | 4dp | 200dp |
| `Lockscreen` | 28 | 3dp | 3dp | 40dp |
| `Mini` | 14 | 3dp | 2dp | 22dp |

The player publishes 64 spectrum bands; a meter maps them onto its own column
count. Variety between columns comes from level and timing, never from
randomised colour. Surfaces that cannot animate — the widget, the notification —
get a static rule instead, not a frozen meter.

## Icons

Hand-drawn geometry only: rectangles, triangles, straight paths, on 18, 20, 24
and 48 unit viewports. Solid icons fill; outline icons stroke with butt caps and
miter joins. No rounded caps, no gradients, no emoji, no icon font. Colour comes
from the `Icon` tint so icons inherit the palette like everything else.

## Copy

Lowercase and terse, the way a config file talks: `add host`, `re-index`,
`purge cache`, `nothing connected yet`. Paths, hosts and fingerprints appear
verbatim — `bjarneo@nas:22`, `/srv/music`, `SHA256:S/3Kx72…`. Never sentence-case
a UI label, never end one with punctuation, never use an exclamation mark. Empty
states say what is absent (`nothing here`), not what to do about it.

## Porting this

For a second client, the order that matters:

1. **Ship the palette contract first.** All thirty-two roles, one theme. Getting
   this wrong is unrecoverable later, because every component reads roles.
2. **Two families, correctly split.** Numeric readouts in the mono face and
   nothing else in it.
3. **Rows before cards**, hairlines before shadows, 22dp gutter.
4. **The mechanical key**, with both the recessed and the shelf build. It is
   most of the app's character.
5. **The brick meter** last — it is the signature, but it is also the only piece
   that is pure decoration.
