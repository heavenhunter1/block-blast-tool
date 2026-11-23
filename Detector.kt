package com.huy.blockblast

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Vùng bàn cờ và khay khối, tính bằng pixel màn hình. */
data class Geo(
    val bx0: Float, val by0: Float, val bx1: Float, val by1: Float,
    val tx0: Float, val ty0: Float, val tx1: Float, val ty1: Float
)

class Frame(val w: Int, val h: Int, private val data: ByteArray, private val rowStride: Int, private val pixStride: Int) {
    /** Độ sáng = kênh màu lớn nhất (ô có khối màu rực -> cao, ô trống tối -> thấp). */
    fun bright(x: Int, y: Int): Int {
        val xi = x.coerceIn(0, w - 1)
        val yi = y.coerceIn(0, h - 1)
        val i = yi * rowStride + xi * pixStride
        val r = data[i].toInt() and 0xFF
        val g = data[i + 1].toInt() and 0xFF
        val b = data[i + 2].toInt() and 0xFF
        return max(r, max(g, b))
    }

    fun avg(cx: Int, cy: Int, rad: Int): Int {
        var s = 0
        for (dy in -1..1) for (dx in -1..1) s += bright(cx + dx * rad, cy + dy * rad)
        return s / 9
    }
}

class Detection(val board: Long, val pieces: List<Cells>, val thr: Int)

object Detector {
    fun detect(f: Frame, g: Geo, ratio: Float): Detection {
        val cw = (g.bx1 - g.bx0) / 8f
        val ch = (g.by1 - g.by0) / 8f
        val rad = (min(cw, ch) * 0.25f).toInt().coerceAtLeast(1)

        val vals = IntArray(64)
        for (r in 0 until 8) for (c in 0 until 8) {
            val cx = (g.bx0 + (c + 0.5f) * cw).toInt()
            val cy = (g.by0 + (r + 0.5f) * ch).toInt()
            vals[r * 8 + c] = f.avg(cx, cy, rad)
        }
        val lo = vals.min()
        val hi = vals.max()
        // Có cả ô trống lẫn ô đầy -> lấy ngưỡng giữa; nếu không thì dùng ngưỡng cố định
        val thr = if (hi - lo > 60) (lo + hi) / 2 else 140

        var board = 0L
        for (i in 0 until 64) if (vals[i] > thr) board = board or (1L shl i)

        val prior = ratio * (cw + ch) / 2f
        val slotW = (g.tx1 - g.tx0) / 3f
        val pieces = (0 until 3).map { k ->
            detectPiece(
                f,
                (g.tx0 + k * slotW).toInt(), g.ty0.toInt(),
                (g.tx0 + (k + 1) * slotW).toInt(), g.ty1.toInt(),
                thr, prior
            )
        }
        return Detection(board, pieces, thr)
    }

    private fun detectPiece(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, thr: Int, prior: Float): Cells {
        val step = 3
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                if (f.bright(x, y) > thr) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (count < 6) return emptyList()

        val w = (maxX - minX + step).toFloat()
        val h = (maxY - minY + step).toFloat()
        var best: Cells = emptyList()
        var bestScore = Float.MAX_VALUE
        // Thử mọi lưới n x m (tối đa 5x5), chọn lưới hợp lý nhất so với cỡ ô dự kiến
        for (n in 1..5) for (m in 1..5) {
            val sx = w / n
            val sy = h / m
            if (abs(sx - sy) > 0.25f * max(sx, sy)) continue
            val s = (sx + sy) / 2f
            val cells = ArrayList<Pair<Int, Int>>()
            for (i in 0 until m) for (j in 0 until n) {
                val cx = (minX + (j + 0.5f) * sx).toInt()
                val cy = (minY + (i + 0.5f) * sy).toInt()
                if (f.avg(cx, cy, (s * 0.2f).toInt().coerceAtLeast(1)) > thr) cells.add(i to j)
            }
            if (cells.isEmpty()) continue
            val norm = Solver.normalize(cells)
            if (norm.maxOf { it.first } + 1 != m || norm.maxOf { it.second } + 1 != n) continue
            var score = abs(s - prior) / prior
            if (norm !in Solver.KNOWN) score += 0.6f
            if (score < bestScore) { bestScore = score; best = norm }
        }
        return best
    }
}
