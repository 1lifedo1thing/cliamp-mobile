package stream.kleeamp.mobile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The bundled cover designs, used when an item has no artwork of its own.
 * Each design is a square PNG in assets/covers. The pick is random across
 * items but stable for one item, so a station keeps the same cover across
 * recompositions and launches.
 */
object PlaceholderArt {

    const val COUNT = 50

    private val cache = HashMap<Int, Bitmap?>()

    fun indexFor(key: String): Int {
        val hash = key.hashCode()
        return ((hash % COUNT) + COUNT) % COUNT
    }

    fun bitmapFor(context: Context, key: String): Bitmap? {
        val index = indexFor(key)
        synchronized(cache) {
            if (cache.containsKey(index)) return cache[index]
        }
        val bitmap = runCatching {
            context.assets.open("covers/cover_%02d.png".format(index + 1))
                .use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        synchronized(cache) { cache[index] = bitmap }
        return bitmap
    }
}
