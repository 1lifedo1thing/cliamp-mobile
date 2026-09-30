package stream.kleeamp.mobile.playback

/**
 * Lock-free-ish mono PCM ring fed by the Media3 audio tap.
 *
 * The tap runs on ExoPlayer's audio thread (playback priority). Writes must
 * never block, allocate, or touch the UI. Reads happen on the analyzer
 * coroutine (~30 Hz) which copies the latest window for FFT/waveform.
 *
 * Contents are mono mix in -1..1 (stereo averaged, mono passed through).
 * This is the SAME PCM that reaches playback: local songs, podcast files,
 * podcast streams, radio (MP3/AAC/HLS), provider tracks (Navidrome, Jellyfin,
 * Emby, Plex, Audiobookshelf, Lyrion), SSH/SFTP, M3U/M3U8/PLS — anything
 * Media3 decodes flows through the audio sink chain, so visualization is
 * source-agnostic by construction. No duration or seek position is consulted,
 * so live/unknown-duration streams visualize continuously.
 */
object PcmRing {
    const val CAPACITY = 16_384

    private val buf = FloatArray(CAPACITY)
    private var writePos = 0L

    @Volatile var sampleRateHz: Int = 44_100
        private set

    @Volatile var channels: Int = 2
        private set

    @Synchronized
    fun onFormat(sampleRate: Int, channelCount: Int) {
        if (sampleRate > 0) sampleRateHz = sampleRate
        if (channelCount > 0) channels = channelCount
    }

    /** Write mono samples; called on the audio thread. Never allocates. */
    @Synchronized
    fun writeMono(samples: FloatArray, count: Int) {
        var n = count.coerceAtMost(samples.size)
        var src = 0
        while (n > 0) {
            val idx = (writePos % CAPACITY).toInt()
            val room = CAPACITY - idx
            val chunk = minOf(n, room)
            samples.copyInto(buf, idx, src, src + chunk)
            src += chunk
            n -= chunk
            writePos += chunk
        }
    }

    /** Total samples ever written; for staleness detection. */
    @Synchronized
    fun written(): Long = writePos

    /**
     * Copy the latest [count] mono samples into [dst] (oldest-first).
     * Missing history is zero-filled. Returns samples actually copied from
     * the ring (rest are zeros).
     */
    @Synchronized
    fun readLatest(dst: FloatArray, count: Int): Int {
        val want = count.coerceAtMost(dst.size)
        if (want <= 0) return 0
        val available = minOf(writePos, CAPACITY.toLong()).toInt()
        val have = minOf(want, available)
        val zeros = want - have
        if (zeros > 0) dst.fill(0f, 0, zeros)
        if (have > 0) {
            val start = writePos - have
            var dstPos = zeros
            var remaining = have
            var srcPos = start
            while (remaining > 0) {
                val idx = (srcPos % CAPACITY).toInt()
                val chunk = minOf(remaining, CAPACITY - idx)
                buf.copyInto(dst, dstPos, idx, idx + chunk)
                dstPos += chunk
                srcPos += chunk
                remaining -= chunk
            }
        }
        return have
    }

    @Synchronized
    fun clear() {
        buf.fill(0f)
        writePos = 0L
    }
}
