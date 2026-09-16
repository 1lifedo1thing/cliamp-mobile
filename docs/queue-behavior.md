# Playback and Up next

Up next is one ordered list. Its first entry is what Next plays. Finishing a
song or podcast episode follows that same order. Playback consumes entries;
it does not rebuild the queue after each track.

## Playing from a list

The player remembers which list started the queue for the current session.

| Action | Result |
| --- | --- |
| Browse another screen or list | Playback and Up next stay unchanged. |
| Play an item from the same list | Play it now; keep every pending entry in its existing order. |
| Play an item from a different list | Replace Up next with the items following the selected item in that list. No confirmation. |
| Tap an entry inside Up next | Jump to that occurrence; entries after it remain upcoming. |
| Next, or a finite item finishes | Play the first upcoming entry. At the end, stop. |

Selecting from the same list is independent of selecting inside Up next.
For example:

1. A is playing from a playlist. You arrange Up next as **D → B → C → E**.
2. You tap C in that original playlist.
3. C plays now. Up next is still **D → B → C → E**.
4. Next plays D. C will play again when its queued turn arrives.

This also preserves removals, manual additions, and an intentionally empty
queue. Tapping the already playing item does not add another pending copy.

## What counts as the same list?

Identity belongs to the list, not its current songs or its screen instance:

- A user playlist uses its stable playlist ID.
- A provider album uses the account ID and album ID; each account's all-songs
  list has its own identity.
- A library collection uses its kind and membership filter (for example, a
  local folder or a favourites category).
- A podcast show uses its feed URL.
- Radio uses the section and, for directory results, the directory query.
- Search uses its query and category filter.

Reopening a list, renaming a playlist, changing its sort, or refreshing its
contents does not overwrite an active queue. Selecting a different folder,
album, playlist, show, or search result set starts a different list. Merely
browsing any of them never does.

## Podcasts and radio

Playing an episode from a show while music is playing switches the source to
that show. The following episodes are queued in the order displayed by the
show screen. Playing another episode from that same show preserves Up next.
Switching back to a music playlist starts a fresh music queue; the previous
music arrangement is not saved as a second queue.

Radio follows the same source rules. A live station plays until you stop it or
press Next; it has no natural end. Finite songs and episodes in a mixed queue
still advance to the next entry, including a live station.

## Editing and clearing

- Drag a handle to reorder upcoming entries. Swipe left to remove one.
- **Play next** inserts immediately after the current item.
- **Add to queue** appends to the end of Up next.
- Both insertion actions work across music, radio, and podcasts without
  changing the originating list or replacing its order.
- **Clear** in Up next removes all pending entries, including the continuation
  of a long list. The current item keeps playing. No confirmation.
- Playing from another list replaces the queue immediately. Returning later
  to the old list and playing starts it afresh.

Shuffle is an explicit change of order. With shuffle already enabled, playing
from a different list creates a shuffled queue; playing from the same list
still preserves the existing order. Previous remains playback-history
navigation; Next always follows the current Up next order.

## Implementation contract

`PlayerConnection` owns the source identity and playback sequence. Screens
provide an explicit `PlaybackContext` only on a play action. Queue taps target
an occurrence index, since the same song can appear more than once.

Media3 and transport navigation must use that sequence. Long-list edits must
retain entries beyond the loaded playback window, and advancing the window
must preserve the current item's playback position. Queue/source identity is
session state; this change does not add full queue restoration after process
death.
