package stream.kleeamp.mobile.player.vis

/**
 * Scrolling mountain silhouette, mirroring cliamp's terrainDriver: new
 * spectrum energy enters from the right and scrolls left, each column
 * filled from its height down. Green valleys, yellow slopes, red peaks.
 */
class TerrainCore(val capacity: Int) {
    private val buf = FloatArray(capacity)
    private var count = 0

    /** Heights oldest-first, zero-padded left until full. */
    fun heights(): FloatArray {
        if (count < capacity) {
            val out = FloatArray(capacity)
            buf.copyInto(out, capacity - count, 0, count)
            return out
        }
        return buf.copyOf()
    }

    fun push(env: Float) {
        if (capacity <= 0) return
        if (count < capacity) {
            buf[count++] = env.coerceIn(0f, 1f)
        } else {
            buf.copyInto(buf, 0, 1, capacity)
            buf[capacity - 1] = env.coerceIn(0f, 1f)
        }
    }

    fun settle() {
        buf.fill(0f)
        count = 0
    }
}
