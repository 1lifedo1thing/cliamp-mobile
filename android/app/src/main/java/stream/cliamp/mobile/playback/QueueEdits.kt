package stream.cliamp.mobile.playback

/** Follow an occurrence, including when another item crosses it or URLs repeat. */
internal fun queueIndexAfterMove(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < current && to >= current -> current - 1
    from > current && to <= current -> current + 1
    else -> current
}
