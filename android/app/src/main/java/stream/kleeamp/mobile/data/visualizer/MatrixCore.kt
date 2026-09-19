package stream.kleeamp.mobile.data.visualizer

data class MatrixCell(val char: Char, val tier: Int)

object MatrixCore {

    private val chars = charArrayOf(
        'ｦ', 'ｧ', 'ｨ', 'ｩ', 'ｪ', 'ｫ', 'ｬ', 'ｭ', 'ｮ', 'ｯ',
        'ｰ', 'ｱ', 'ｲ', 'ｳ', 'ｴ', 'ｵ', 'ｶ', 'ｷ', 'ｸ', 'ｹ', 'ｺ',
        'ｻ', 'ｼ', 'ｽ', 'ｾ', 'ｿ', 'ﾀ', 'ﾁ', 'ﾂ', 'ﾃ', 'ﾄ',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    )

    fun cell(energy: Float, col: Int, row: Int, rows: Int, frame: Long): MatrixCell? {
        if (VisMath.scatterHash(col, 0, col, frame / 20) > energy * 1.5f + 0.1f) return null

        val seed = col.toULong() * 7919uL + 104729uL
        val speed = 2 + (seed % 3uL).toInt()
        val trailLen = 3 + ((seed / 7uL) % 3uL).toInt()
        val cycleLen = rows + trailLen + 4
        val offset = ((seed / 13uL) % cycleLen.toULong()).toInt()
        val pos = ((frame / speed).toInt() + offset) % cycleLen
        val dist = pos - row
        if (dist < 0 || dist > trailLen) return null

        val charSeed = seed xor (row.toULong() * 31uL + (frame / 4).toULong() * 17uL)
        val char = chars[(charSeed % chars.size.toULong()).toInt()]
        val tier = when {
            dist == 0 -> 2
            dist <= 2 -> 1
            else -> 0
        }
        return MatrixCell(char, tier)
    }
}
