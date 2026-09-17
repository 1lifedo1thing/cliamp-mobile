# Playback and Up next

Up next is one ordered list. Its first entry is what Next plays. Finishing a
song or podcast episode follows that same order. Playback consumes entries;
it does not rebuild the queue after each track.

## Playing from a list

Playing an item from any list starts that list at the tapped item: Up next is
rebuilt from the items following it in that list, in the order the list
shows. This holds for playlists, library collections, provider albums,
podcast shows, radio lists and search results alike.

| Action | Result |
| --- | --- |
| Play an item from a list | Replace Up next with the items following it in that list. |
| Browse another screen or list | Playback and Up next stay unchanged. |
| Tap an entry inside Up next | Jump to that occurrence; entries after it remain upcoming. |
| Drag, remove, Play next, Add to queue | Edit the pending entries in place. |
| Next, or a finite item finishes | Play the first upcoming entry. At the end, stop. |

Edits to Up next survive browsing. A play from a list always rebuilds Up next
from the items following the tapped item in that list, discarding the previous
arrangement. Removals, manual additions and an intentionally empty queue
persist until the next list tap.

## Podcasts and radio

Playing an episode from a show queues the following episodes in the order
displayed by the show screen. Playing another episode from that show restarts
the queue from that episode, the same as any other list. Switching between
music, podcasts and radio always starts the tapped list afresh.

Radio follows the same rules. A live station plays until you stop it or press
Next; it has no natural end. Finite songs and episodes in a mixed queue still
advance to the next entry, including a live station.

## Editing and clearing

- Drag a handle to reorder upcoming entries. Swipe left to remove one.
- **Play next** inserts immediately after the current item.
- **Add to queue** appends to the end of Up next.
- Both insertion actions work across music, radio, and podcasts without
  changing the originating list or replacing its order.
- **Clear** in Up next removes all pending entries, including the continuation
  of a long list. The current item keeps playing. No confirmation.
- Playing from a list replaces the queue immediately. Returning later to the
  old list and playing starts it afresh.

Shuffle is an explicit change of order. With shuffle already enabled, playing
from a list creates a shuffled queue headed by the tapped item. Previous
remains playback-history navigation; Next always follows the current Up next
order.

## Implementation contract

`PlayerConnection` owns the playback sequence. Screens provide the list on a
play action. Queue taps target an occurrence index, since the same song can
appear more than once. Media3 and transport navigation must use that sequence.
Long-list edits must retain entries beyond the loaded playback window, and
advancing the window must preserve the current item's playback position. The
queue is session state; this change does not add full queue restoration after
process death.
