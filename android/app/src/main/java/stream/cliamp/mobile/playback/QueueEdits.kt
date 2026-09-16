package stream.cliamp.mobile.playback

/** Follow an occurrence, including when another item crosses it or URLs repeat. */
internal fun queueIndexAfterMove(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < current && to >= current -> current - 1
    from > current && to <= current -> current + 1
    else -> current
}

/** Upcoming items follow the current occurrence; earlier items remain available to Prev. */
internal fun upNextIndices(size: Int, current: Int): IntRange =
    (if (current in 0 until size) current + 1 else 0) until size
