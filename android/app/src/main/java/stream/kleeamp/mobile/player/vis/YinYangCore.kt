package stream.kleeamp.mobile.player.vis

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.atan2

/**
 * Two koi circling a lily pad, ported from cliamp's yinYangDriver
 * (`ui/vis_yinyang.go`): each koi is a chain of 13 joints drawn in dot
 * pixels, the kick/snare/hat onsets flick tails, swirls trail flicks, and
 * the lotus breathes with the bass against its own two-second range.
 *
 * Simulation constants, onset gaps, pace, stroke, sprite hold, gap closing,
 * eyes, sway and petal tables all match the TUI exactly. The only
 * deliberate difference is the final mile: the terminal packs pixels into
 * sextant glyphs (two colors per cell), while here each lit dot draws as
 * its own rect so the phone needs no sextant font — shapes, motion and
 * tag colors are the same.
 */
class YinYangCore {

    var dotCols: Int = 0
        private set
    var dotRows: Int = 0
        private set

    /** Tag per dot cell, row-major. 0 = water, 1 = dim, 2 = pad, 3 = red, 4 = ink, 5 = gold. */
    var cells: ByteArray = ByteArray(0)
        private set

    private var w: Int = 0
    private var h: Int = 0
    private var strip: Boolean = false
    private var cx: Double = 0.0
    private var cy: Double = 0.0
    private var radius: Double = 0.0
    private var size: Double = 0.0
    private var pad: Double = 0.0
    private var lap: Double = 0.0
    private var trackLen: Double = 0.0
    private var waves: Double = 0.0
    private var waveArc: DoubleArray = DoubleArray(0)

    private var angle: Double = 0.0
    private var loud: Double = 0.3
    private var mood: Double = 0.3
    private var bloom: Double = 0.4
    private var breath: Double = 0.0
    private val bassLevels = DoubleArray(BASS_STEPS)
    private var bassNext: Int = 0
    private var bassCount: Int = 0

    private var lo: DoubleArray = DoubleArray(0)
    private var hi: DoubleArray = DoubleArray(0)
    private var prev: DoubleArray? = null
    private val kick = Onset(from = 0.0, to = 0.3, k = 2.0, floor = 0.05, gap = 6)
    private val snare = Onset(from = 0.3, to = 0.6, k = 2.0, floor = 0.04, gap = 5)
    private val hat = Onset(from = 0.6, to = 1.0, k = 1.8, floor = 0.025, gap = 4)

    private val koi = arrayOf(Koi(), Koi())
    private val swirls = ArrayList<Swirl>()
    private var fine: ByteArray = ByteArray(0)

    init {
        reset()
    }

    fun reset() {
        w = 0
        h = 0
        dotCols = 0
        dotRows = 0
        cells = ByteArray(0)
        angle = 0.0
        loud = 0.3
        mood = 0.3
        bloom = 0.4
        breath = 0.0
        bassNext = 0
        bassCount = 0
        lo = DoubleArray(0)
        hi = DoubleArray(0)
        prev = null
        kick.reset()
        snare.reset()
        hat.reset()
        swirls.clear()
    }

    fun ensure(rows: Int, cols: Int) {
        if (rows == dotRows && cols == dotCols && cells.size == rows * cols && w == cols && h == rows) {
            return
        }
        layout(cols, rows)
    }

    fun tagAt(x: Int, y: Int): Int {
        if (x !in 0 until dotCols || y !in 0 until dotRows) return 0
        return cells[y * dotCols + x].toInt()
    }

    fun settle() {
        reset()
    }

    private fun layout(nw: Int, nh: Int) {
        w = nw
        h = nh
        dotCols = nw
        dotRows = nh
        cells = ByteArray(nw * nh)
        val wd = nw.toDouble()
        val hd = nh.toDouble()
        cx = wd / 2
        cy = hd / 2
        strip = wd > hd * 3
        if (strip) {
            size = min(hd * 0.84 / (2 * HALF_BODY), wd / 80)
            radius = min(hd * 0.3, size * 4.5)
            val hidden = SEG * (JOINTS - 1) + NOSE + TAIL_FIN
            lap = round(wd + hidden * size)
            waves = max(1.0, round(lap / (55 * size)))
            pad = 0.0
            val wave = lap / waves
            val arc = DoubleArray(ARC_STEPS + 1)
            arc[0] = 0.0
            for (i in 1..ARC_STEPS) {
                val x0 = (i - 1).toDouble() * wave / ARC_STEPS
                val x1 = i.toDouble() * wave / ARC_STEPS
                val dy = (cos(2 * PI * x1 / wave) - cos(2 * PI * x0 / wave)) * radius
                arc[i] = arc[i - 1] + hypot(x1 - x0, dy)
            }
            waveArc = arc
            trackLen = waves * arc[ARC_STEPS]
        } else {
            val m = min(min(wd, hd), 160.0)
            radius = m * 0.36
            lap = 2 * PI * radius
            trackLen = lap
            pad = m * 0.17
            size = min(min(m * 0.34 * 1.35 / 18, (wd / 2 - radius) / HALF_BODY), (hd / 2 - radius) / HALF_BODY)
        }
        koi[0] = Koi(stroke = 0.6, flickDir = 1.0)
        koi[1] = Koi(offset = PI, phase = 1.5, stroke = 0.6, flickDir = 1.0)
        if (strip) {
            koi[0].offset = -toAngle(GAP)
            koi[1].offset = 0.0
        }
        for (k in koi) {
            lineUp(k)
            k.prevJoints = k.joints.map { it.copy() }.toTypedArray()
        }
        swirls.clear()
    }

    private fun toAngle(dist: Double): Double = dist * size * 2 * PI / trackLen

    private fun lineUp(k: Koi) {
        var a = angle + k.offset
        for (j in 0 until JOINTS) {
            k.joints[j] = trackPoint(a)
            a -= toAngle(SEG)
        }
        follow(k)
    }

    private fun trackPoint(a: Double): Pt {
        if (!strip) {
            return Pt(cx + cos(a) * radius, cy + sin(a) * radius)
        }
        val per = waveArc[ARC_STEPS]
        var s = a / (2 * PI) * trackLen
        val n = floor(s / per)
        s -= n * per
        var i = lowerBound(waveArc, s)
        if (i < 1) i = 1
        if (i > ARC_STEPS) i = ARC_STEPS
        val denom = waveArc[i] - waveArc[i - 1]
        val t = if (denom == 0.0) 0.0 else (s - waveArc[i - 1]) / denom
        val wave = lap / waves
        val x = (i - 1 + t) * wave / ARC_STEPS
        return Pt(n * wave + x + w.toDouble() * 0.6, cy + cos(2 * PI * x / wave) * radius)
    }

    private fun lowerBound(a: DoubleArray, s: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < s) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * One fixed 50 ms step, exactly like cliamp's advance: per-band
     * envelopes, drum onsets flicking tails, bass history breathing the
     * lotus, pace from loud/mood, stroke easing, joint follow, swirl spawn
     * and expiry.
     */
    fun advance(bands: FloatArray, playing: Boolean = true) {
        val n = bands.size
        if (lo.size != n) {
            lo = DoubleArray(n) { 1.0 }
            hi = DoubleArray(n)
            prev = null
        }
        var energy = 0.0
        var avg = 0.0
        for (i in 0 until n) {
            val b = bands[i].toDouble()
            energy += b
            hi[i] = max(b, hi[i] - 0.004)
            lo[i] = min(b, lo[i] + 0.004)
            avg += min(1.0, max(0.0, (b - lo[i]) / max(hi[i] - lo[i], 0.12)))
        }
        if (n > 0) {
            energy /= n
            avg /= n
        }
        val quiet = !playing || energy < QUIET
        if (playing) {
            loud += (avg - loud) * 0.05
            mood += (avg - mood) * 0.01
            var bloomTarget = 0.0
            if (!quiet) {
                bloomTarget = min(1.0, max(0.0, (mood - 0.1) / 0.5))
            }
            bloom += (bloomTarget - bloom) * 0.02
        }

        val prevBands = prev
        if (prevBands != null) {
            val bd = bands.map { it.toDouble() }.toDoubleArray()
            val drums = arrayOf(
                Triple(kick, 0, strip),
                Triple(snare, 0, !strip),
                Triple(hat, 1, true),
            )
            for ((onset, ki, here) in drums) {
                val (hit, strength) = onset.step(bd, prevBands)
                val k = koi[ki]
                if (hit && here && !quiet && strength >= STANDOUT && k.flickRest == 0) {
                    k.flickTail(min(MAX_POWER, 0.6 + 0.3 * strength))
                    k.flickRest = REST
                }
            }
        }
        prev = bands.map { it.toDouble() }.toDoubleArray()

        breath *= 0.8
        if (playing && n > 0) {
            val low = max(1, n / 5)
            var level = 0.0
            for (i in 0 until low) level += bands[i] / low
            bassLevels[bassNext] = level
            bassNext = (bassNext + 1) % BASS_STEPS
            bassCount = min(bassCount + 1, BASS_STEPS)
            if (!quiet && !strip) {
                var loB = level
                var hiB = level
                for (i in 0 until bassCount) {
                    loB = min(loB, bassLevels[i])
                    hiB = max(hiB, bassLevels[i])
                }
                breath = (level - loB) / max(hiB - loB, 0.08)
            }
        }

        var pace = 0.0
        if (playing) {
            pace = when {
                quiet -> 0.066
                strip -> 0.11 + 0.22 * loud
                else -> 0.09 + 0.13 * mood
            }
            if (strip) {
                var least = 0.7
                if (!quiet) least = 1.0 + 1.0 * loud
                pace = max(pace * STRIP_PACE, least / size)
            }
            angle += toAngle(pace)
        }
        var stroke = 0.1
        var beat = 0.09
        if (!quiet) {
            stroke = 0.3 + mood
            beat = 0.125 + 0.085 * mood
        }
        for (k in koi) {
            k.prevJoints = k.joints.map { it.copy() }.toTypedArray()
            k.phaseStep = 0.0
            k.flickRest = max(0, k.flickRest - 1)
            k.stroke += (stroke - k.stroke) * 0.02
            if (playing) {
                k.phaseStep = beat
                k.phase += beat
                k.joints[0] = trackPoint(angle + k.offset)
                follow(k)
            }
            if (k.flick == FLICK_STEPS && !strip) {
                val tail = k.joints[JOINTS - 1]
                val root = k.joints[JOINTS - 2]
                val out = atan2(tail.y - root.y, tail.x - root.x) + k.flickDir * PI / 2
                val x = tail.x + cos(out) * 1.2 * size
                val y = tail.y + sin(out) * 1.2 * size
                swirls.add(Swirl(x = x, y = y, angle = out, spin = k.flickDir, life = SWIRL_LIFE))
            }
            if (k.flick > 0) k.flick--
        }
        val live = swirls.filter { it.life--; it.life > 0 }
        swirls.clear()
        swirls.addAll(live)
    }

    private fun follow(k: Koi) {
        val seg = SEG * size
        for (j in 1 until JOINTS) {
            val a = k.joints[j - 1]
            val b = k.joints[j]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dist = hypot(dx, dy)
            if (dist > 0) {
                k.joints[j] = Pt(a.x + dx / dist * seg, a.y + dy / dist * seg)
            }
        }
    }

    /** Fill [cells] from current koi positions, swirls, pad and lotus. */
    fun draw() {
        if (w <= 0 || h <= 0 || cells.size != w * h) return
        cells.fill(0)
        for (s in swirls) drawSwirl(s)
        for (i in koi.indices) {
            drawKoi(koi[i], PATTERNS[i])
            koi[i].sprite = koi[i].pendingSprite ?: koi[i].sprite
        }
        val notchX = cos(0.9)
        val notchY = sin(0.9)
        val notchTan = kotlin.math.tan(0.28)
        disc(cx, cy, pad) { dx, dy ->
            val ahead = dx * notchX + dy * notchY
            val aside = dx * notchY - dy * notchX
            if (ahead > 0 && abs(aside) < notchTan * ahead && dx * dx + dy * dy > 0.04) -1 else TAG_PAD
        }
        val (outer, inner) = lotusReach()
        drawLotus(outer, inner)
    }

    private fun lotusReach(): Pair<Double, Double> =
        (0.5 + 0.12 * bloom + 0.2 * breath) to (0.3 + 0.08 * bloom + 0.08 * breath)

    private fun drawLotus(outer: Double, inner: Double) {
        val petals = 6
        val rings = arrayOf(
            Triple(pad * outer, 0.0, TAG_INK),
            Triple(pad * inner, PI / petals, TAG_RED),
        )
        for ((length, turn, tag) in rings) {
            disc(cx, cy, length) { dx, dy ->
                var a = atan2(dy, dx) - turn
                a -= round(a / (2 * PI / petals)) * 2 * PI / petals
                val r = hypot(dx, dy)
                val along = r * cos(a)
                val across = r * sin(a)
                if (along > 0 && along < 1 && abs(across) <= petalWidth(along)) tag else -1
            }
        }
        fill(cx, cy, pad * inner * 0.35, TAG_GOLD)
    }

    private fun petalWidth(along: Double): Double {
        val f = along * 64
        val i = f.toInt().coerceIn(0, 63)
        return PETAL[i] + (PETAL[i + 1] - PETAL[i]) * (f - i)
    }

    private fun set(x: Int, y: Int, t: Byte) {
        var xx = x
        if (strip) {
            val lapI = lap.toInt()
            if (lapI != 0) {
                xx %= lapI
                if (xx < 0) xx += lapI
            }
        }
        if (t < 0 || xx < 0 || y < 0 || xx >= w || y >= h) return
        cells[y * w + xx] = t
    }

    private fun plot(x: Double, y: Double, t: Byte) {
        set(floor(x).toInt(), floor(y).toInt(), t)
    }

    private fun disc(cx: Double, cy: Double, r: Double, shade: (Double, Double) -> Byte) {
        if (r <= 0) return
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(h - 1, ceil(cy + r).toInt())
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(w - 1, ceil(cx + r).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = (x + 0.5 - cx) / r
                val dy = (y + 0.5 - cy) / r
                if (dx * dx + dy * dy <= 1) {
                    set(x, y, shade(dx, dy))
                }
            }
        }
    }

    private fun fill(cx: Double, cy: Double, r: Double, t: Byte) {
        disc(cx, cy, r) { _, _ -> t }
    }

    private fun drawSwirl(s: Swirl) {
        val open = (SWIRL_LIFE - s.life).toDouble() / SWIRL_LIFE
        val r = (1 + 2.5 * open) * size
        val points = 14
        for (i in 0..points) {
            val t = i.toDouble() / points
            if (t >= 1 - open * 0.6) break
            val a = s.angle + t * 4.5 * s.spin
            val rr = r * (0.4 + 0.6 * t)
            plot(s.x + cos(a) * rr, s.y + sin(a) * rr, TAG_DIM)
        }
    }

    private fun drawKoi(k: Koi, pat: Pattern) {
        if (strip) {
            val head = k.joints[0]
            val sp = k.sprite
            val hx = floor(head.x).toInt()
            val hy = floor(head.y).toInt()
            if (sp.px == null || hx != sp.hx || hy != sp.hy || k.flick > 0 || sp.flicked) {
                drawSprite(k, pat)
            }
            val cur = k.pendingSprite ?: k.sprite
            val px = cur.px ?: return
            for (idx in px.indices) {
                val t = px[idx]
                if (t != TAG_WATER) {
                    set(cur.hx + cur.dx + idx % cur.w, cur.hy + cur.dy + idx / cur.w, t)
                }
            }
            return
        }
        val reach = ceil(HALF_BODY * size) + 2
        var x0 = k.joints[0].x
        var y0 = k.joints[0].y
        var x1 = x0
        var y1 = y0
        for (p in k.joints) {
            x0 = min(x0, p.x)
            x1 = max(x1, p.x)
            y0 = min(y0, p.y)
            y1 = max(y1, p.y)
        }
        val ox = floor(x0) - reach
        val oy = floor(y0) - reach
        val bw = (ceil(x1) + reach - ox).toInt() + 1
        val bh = (ceil(y1) + reach - oy).toInt() + 1
        if (bw <= 0 || bh <= 0) return
        if (fine.size < bw * bh) fine = ByteArray(bw * bh)
        val buf = fine.copyOf(bw * bh).also { it.fill(0) }
        val local = k.copy()
        for (i in local.joints.indices) {
            val p = k.joints[i]
            local.joints[i] = Pt(p.x - ox, p.y - oy)
        }
        drawShapeInto(buf, bw, bh, local, pat, swim = true)
        closeGaps(buf, bw, pat.body)
        for (i in buf.indices) {
            val t = buf[i]
            if (t != TAG_WATER) {
                set(ox.toInt() + i % bw, oy.toInt() + i / bw, t)
            }
        }
        val j = k.joints
        val head = atan2(j[0].y - j[2].y, j[0].x - j[2].x)
        val r = RADII[0] * size * 0.75
        for (side in doubleArrayOf(-1.0, 1.0)) {
            val a = head + side * 1.1
            plot(j[0].x + cos(a) * r, j[0].y + sin(a) * r, TAG_WATER)
        }
    }

    private fun drawSprite(k: Koi, pat: Pattern) {
        val head = k.joints[0]
        val hx = floor(head.x)
        val hy = floor(head.y)
        val sx = hx + 0.5 - head.x
        val sy = hy + 0.5 - head.y
        val reach = ceil(HALF_BODY * size) + 1
        var x0 = hx
        var y0 = hy
        var x1 = hx
        var y1 = hy
        for (p in k.joints) {
            x0 = min(x0, floor(p.x + sx))
            x1 = max(x1, floor(p.x + sx))
            y0 = min(y0, floor(p.y + sy))
            y1 = max(y1, floor(p.y + sy))
        }
        val ox = x0 - reach
        val oy = y0 - reach
        val bw = (x1 - x0 + 2 * reach).toInt() + 1
        val bh = (y1 - y0 + 2 * reach).toInt() + 1
        if (bw <= 0 || bh <= 0) return
        val local = k.copy()
        for (i in local.joints.indices) {
            val p = k.joints[i]
            local.joints[i] = Pt(p.x + sx - ox, p.y + sy - oy)
        }
        val buf = ByteArray(bw * bh)
        // Strip koi are small: draw with a scratch driver of the same size.
        drawShapeInto(buf, bw, bh, local, pat, swim = false)
        closeGaps(buf, bw, pat.body)
        addStripEyes(buf, bw, (hx - ox).toInt(), (hy - oy).toInt(), pat.body)
        k.pendingSprite = Sprite(
            px = buf, w = bw,
            hx = hx.toInt(), hy = hy.toInt(),
            dx = (ox - hx).toInt(), dy = (oy - hy).toInt(),
            flicked = k.flick > 0,
        )
    }

    private fun drawShapeInto(buf: ByteArray, bw: Int, bh: Int, k: Koi, pat: Pattern, swim: Boolean) {
        val j = k.joints
        val sz = size
        var wag = 0.5
        if (swim) {
            wag = 0.6 * k.stroke
            for (i in 4 until JOINTS) {
                val a = k.joints[i - 1]
                val b = k.joints[i]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist = hypot(dx, dy)
                if (dist > 0) {
                    val f = (i - 3).toDouble() / (JOINTS - 4)
                    val off = sin(k.phase - f * 2.5) * f * f * k.stroke * 1.8 * sz
                    j[i] = Pt(j[i].x - dy / dist * off, j[i].y + dx / dist * off)
                }
            }
        }
        if (k.flick > 0) {
            val bend = k.tailBend() * k.flickDir
            val root = j[JOINTS - 5]
            val prv = j[JOINTS - 6]
            val hx = root.x - prv.x
            val hy = root.y - prv.y
            val dist = hypot(hx, hy)
            if (dist > 0) {
                val px = -hy / dist
                val py = hx / dist
                for (i in JOINTS - 5 until JOINTS) {
                    val off = (i - (JOINTS - 6)).toDouble() / 5 * bend * sz * 1.5
                    j[i] = Pt(j[i].x + px * off, j[i].y + py * off)
                }
            }
        }
        val scratch = Scratch(buf, bw, bh)
        val head = atan2(j[0].y - j[2].y, j[0].x - j[2].x)
        val flap = sin(k.phase * 0.5) * 0.25
        for (side in doubleArrayOf(-1.0, 1.0)) {
            val a = head + PI + side * (1.25 + flap)
            for (t in 0 until 3) {
                val off = (RADII[2] + 0.6 + t) * sz
                scratch.fill(j[2].x + cos(a) * off, j[2].y + sin(a) * off, (1.5 - t * 0.35) * sz, pat.body)
            }
        }
        val back = atan2(j[7].y - j[6].y, j[7].x - j[6].x)
        for (side in doubleArrayOf(-1.0, 1.0)) {
            val a = back + side * 1.9
            scratch.fill(j[7].x + cos(a) * 1.6 * sz, j[7].y + sin(a) * 1.6 * sz, 0.8 * sz, pat.body)
        }
        val last = j[JOINTS - 1]
        val before = j[JOINTS - 2]
        val tail = atan2(last.y - before.y, last.x - before.x) + sin(k.phase - 2) * wag
        scratch.fill(last.x + cos(tail) * 1.2 * sz, last.y + sin(tail) * 1.2 * sz, 0.75 * sz, pat.body)
        scratch.fill(last.x + cos(tail) * 0.6 * sz, last.y + sin(tail) * 0.6 * sz, 0.65 * sz, pat.body)
        for (i in JOINTS - 1 downTo 0) {
            scratch.fill(j[i].x, j[i].y, RADII[i] * sz, pat.body)
            if (i > 0) {
                val r = (RADII[i] + RADII[i - 1]) / 2 * sz
                scratch.fill((j[i].x + j[i - 1].x) / 2, (j[i].y + j[i - 1].y) / 2, r, pat.body)
            }
        }
        for (i in JOINTS - 1 downTo 0) {
            val p = pat.back[i]
            if (p != TAG_WATER) {
                scratch.fill(j[i].x, j[i].y, RADII[i] * sz * 0.62, p)
            }
        }
    }

    private inner class Scratch(val buf: ByteArray, val bw: Int, val bh: Int) {
        fun setAt(x: Int, y: Int, t: Byte) {
            if (t < 0 || x < 0 || y < 0 || x >= bw || y >= bh) return
            buf[y * bw + x] = t
        }

        fun fill(fcx: Double, fcy: Double, r: Double, t: Byte) {
            if (r <= 0) return
            val y0 = max(0, floor(fcy - r).toInt())
            val y1 = min(bh - 1, ceil(fcy + r).toInt())
            val x0 = max(0, floor(fcx - r).toInt())
            val x1 = min(bw - 1, ceil(fcx + r).toInt())
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = (x + 0.5 - fcx) / r
                    val dy = (y + 0.5 - fcy) / r
                    if (dx * dx + dy * dy <= 1) setAt(x, y, t)
                }
            }
        }
    }

    private data class Pt(val x: Double, val y: Double)

    private class Koi(
        var joints: Array<Pt> = Array(JOINTS) { Pt(0.0, 0.0) },
        var prevJoints: Array<Pt> = Array(JOINTS) { Pt(0.0, 0.0) },
        var offset: Double = 0.0,
        var phase: Double = 0.0,
        var phaseStep: Double = 0.0,
        var stroke: Double = 0.6,
        var flick: Int = 0,
        var flickRest: Int = 0,
        var flickPower: Double = 0.0,
        var flickDir: Double = 1.0,
        var sprite: Sprite = Sprite(),
        var pendingSprite: Sprite? = null,
    ) {
        fun copy(): Koi = Koi(
            joints = joints.map { it.copy() }.toTypedArray(),
            prevJoints = prevJoints.map { it.copy() }.toTypedArray(),
            offset = offset, phase = phase, phaseStep = phaseStep, stroke = stroke,
            flick = flick, flickRest = flickRest, flickPower = flickPower, flickDir = flickDir,
            sprite = sprite, pendingSprite = pendingSprite,
        )

        fun flickTail(power: Double) {
            if (flick == 0) {
                flickPower = power
                flickDir = -flickDir
            }
            flick = max(flick, FLICK_STEPS - flick)
        }

        fun tailBend(): Double {
            val wave = sin(PI * flick.toDouble() / FLICK_STEPS)
            return 0.6 * wave * wave * flickPower
        }
    }

    private data class Sprite(
        val px: ByteArray? = null,
        val w: Int = 0,
        val hx: Int = 0,
        val hy: Int = 0,
        val dx: Int = 0,
        val dy: Int = 0,
        val flicked: Boolean = false,
    )

    private data class Swirl(var x: Double, var y: Double, var angle: Double, var spin: Double, var life: Int)

    private class Onset(
        val from: Double,
        val to: Double,
        val k: Double,
        val floor: Double,
        val gap: Int,
    ) {
        var mean: Double = 0.03
        var dev: Double = 0.02
        var since: Int = gap

        fun reset() {
            mean = 0.03
            dev = 0.02
            since = gap
        }

        fun step(bands: DoubleArray, prevBands: DoubleArray): Pair<Boolean, Double> {
            val n = bands.size
            var flux = 0.0
            val loI = (from * n).toInt()
            val hiI = (to * n).toInt()
            for (i in loI until hiI) {
                if (i >= prevBands.size) continue
                flux += max(0.0, bands[i] - prevBands[i])
            }
            val threshold = max(mean + k * dev, floor)
            val hit = flux > threshold && since >= gap
            mean = mean * 0.92 + flux * 0.08
            dev = dev * 0.92 + abs(flux - mean) * 0.08
            if (hit) since = 0 else since++
            return hit to flux / threshold
        }
    }

    private data class Pattern(val body: Byte, val back: ByteArray)

    companion object {
        const val TAG_WATER: Byte = 0
        const val TAG_DIM: Byte = 1
        const val TAG_PAD: Byte = 2
        const val TAG_RED: Byte = 3
        const val TAG_INK: Byte = 4
        const val TAG_GOLD: Byte = 5

        private const val JOINTS = 13
        private const val SEG = 1.5
        private const val FLICK_STEPS = 8
        private const val MAX_POWER = 1.4
        private const val SWIRL_LIFE = 12
        private const val STANDOUT = 1.5
        private const val REST = 20
        private const val BASS_STEPS = 40
        private const val QUIET = 0.02
        private const val STRIP_PACE = 2.5
        private const val ARC_STEPS = 64
        private const val GAP = 36.0
        private const val HALF_BODY = 6.3
        private const val NOSE = 2.6
        private const val TAIL_FIN = 1.0
        private const val PI = kotlin.math.PI

        private val RADII = doubleArrayOf(2.5, 2.9, 2.9, 2.7, 2.4, 2.05, 1.75, 1.45, 1.2, 1.0, 0.85, 0.7, 0.6)

        private val PATTERNS = arrayOf(
            Pattern(
                body = TAG_RED,
                back = byteArrayOf(0, TAG_INK, TAG_INK, 0, TAG_INK, TAG_INK, 0, 0, TAG_INK, TAG_INK, 0, TAG_INK, 0),
            ),
            Pattern(
                body = TAG_INK,
                back = byteArrayOf(0, TAG_RED, TAG_RED, 0, 0, TAG_RED, TAG_RED, TAG_RED, 0, 0, 0, 0, 0),
            ),
        )

        private val PETAL: DoubleArray = DoubleArray(65).also { w ->
            for (i in w.indices) {
                w[i] = 0.36 * sin(PI * (i.toDouble() / 64).pow(0.8)).pow(0.6)
            }
        }

        /** cliamp analyzes DefaultSpectrumBands for spectrum modes. */
        const val BANDS = 10

        internal fun closeGaps(px: ByteArray, w: Int, fill: Byte) {
            val h = px.size / w
            if (h <= 0) return
            fun koi(i: Int): Boolean = px[i] != TAG_WATER
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!koi(i) && ((x > 0 && x < w - 1 && koi(i - 1) && koi(i + 1)) ||
                            (y > 0 && y < h - 1 && koi(i - w) && koi(i + w)))
                    ) {
                        px[i] = fill
                    }
                }
            }
        }

        internal fun addStripEyes(px: ByteArray, w: Int, hx: Int, hy: Int, body: Byte) {
            val h = px.size / w
            if (h <= 0) return
            val ex = hx + 1
            var e = hy
            while (e > 1) {
                if (ex < w && hy + e < h && hy - e >= 0 &&
                    px[(hy - e) * w + ex] == body && px[(hy + e) * w + ex] == body
                ) {
                    px[(hy - e + 1) * w + ex] = TAG_WATER
                    px[(hy + e - 1) * w + ex] = TAG_WATER
                    return
                }
                e--
            }
        }
    }
}
