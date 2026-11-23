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
    /** Màu RGB đóng gói 0xRRGGBB. */
    fun rgb(x: Int, y: Int): Int {
        val xi = x.coerceIn(0, w - 1)
        val yi = y.coerceIn(0, h - 1)
        val i = yi * rowStride + xi * pixStride
        val r = data[i].toInt() and 0xFF
        val g = data[i + 1].toInt() and 0xFF
        val b = data[i + 2].toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    /** Độ sáng = kênh màu lớn nhất (ô có khối màu rực -> cao, ô trống tối -> thấp). */
    fun bright(x: Int, y: Int): Int {
        val p = rgb(x, y)
        return max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF))
    }

    /** Màu trung bình 3x3 điểm quanh (cx, cy): [r, g, b]. */
    fun avgRgb(cx: Int, cy: Int, rad: Int): IntArray {
        var r = 0
        var g = 0
        var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val p = rgb(cx + dx * rad, cy + dy * rad)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        return intArrayOf(r / 9, g / 9, b / 9)
    }
}

class Detection(val board: Long, val pieces: List<Cells>, val thr: Int)

object Detector {
    fun detect(f: Frame, g: Geo, ratio: Float): Detection {
        val cw = (g.bx1 - g.bx0) / 8f
        val ch = (g.by1 - g.by0) / 8f
        val rad = (min(cw, ch) * 0.25f).toInt().coerceAtLeast(1)

        // ---- Bàn cờ: so màu từng ô với màu ô trống (ô tối nhất), dùng được cho mọi giao diện
        val cols = Array(64) { i ->
            val r = i / 8
            val c = i % 8
            f.avgRgb((g.bx0 + (c + 0.5f) * cw).toInt(), (g.by0 + (r + 0.5f) * ch).toInt(), rad)
        }
        var dark = 0
        for (i in 1 until 64) if (cols[i].sum() < cols[dark].sum()) dark = i
        val ref = IntArray(3)
        var cnt = 0
        for (i in 0 until 64) {
            if (colorDist(cols[i], cols[dark]) < 45) {
                for (k in 0..2) ref[k] += cols[i][k]
                cnt++
            }
        }
        for (k in 0..2) ref[k] /= cnt
        val thr = 70
        var board = 0L
        for (i in 0 until 64) if (colorDist(cols[i], ref) > thr) board = board or (1L shl i)

        // ---- 3 khối trong khay: so với màu nền của từng ô khay
        val prior = ratio * (cw + ch) / 2f
        val slotW = (g.tx1 - g.tx0) / 3f
        val inset = (slotW * 0.04f).toInt()
        val pieces = (0 until 3).map { k ->
            detectPiece(
                f,
                (g.tx0 + k * slotW).toInt() + inset, g.ty0.toInt() + inset,
                (g.tx0 + (k + 1) * slotW).toInt() - inset, g.ty1.toInt() - inset,
                prior
            )
        }
        return Detection(board, pieces, thr)
    }

    private fun detectPiece(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, prior: Float): Cells {
        val step = 3
        val gw = (x1 - x0) / step
        val gh = (y1 - y0) / step
        if (gw <= 0 || gh <= 0) return emptyList()

        // 1) Màu nền = trung vị từng kênh (khối chiếm ít hơn nửa diện tích ô khay)
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        var yy = y0
        while (yy < y1) {
            var xx = x0
            while (xx < x1) {
                val p = f.rgb(xx, yy)
                rs.add((p shr 16) and 0xFF); gs.add((p shr 8) and 0xFF); bs.add(p and 0xFF)
                xx += 6
            }
            yy += 6
        }
        if (rs.isEmpty()) return emptyList()
        rs.sort(); gs.sort(); bs.sort()
        val bgR = rs[rs.size / 2]
        val bgG = gs[gs.size / 2]
        val bgB = bs[bs.size / 2]

        // 2) Mặt nạ: khối = khác nền nhiều, hoặc lệch hẳn tông màu nền (bóng đổ chỉ tối hơn nên bị loại)
        val bgNorm = (bgR * bgR + bgG * bgG + bgB * bgB).toFloat().coerceAtLeast(1f)
        val mask = BooleanArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val p = f.rgb(x0 + i * step, y0 + j * step)
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val d = abs(pr - bgR) + abs(pg - bgG) + abs(pb - bgB)
            val k = (pr * bgR + pg * bgG + pb * bgB) / bgNorm
            val res = abs(pr - k * bgR) + abs(pg - k * bgG) + abs(pb - k * bgB)
            mask[j * gw + i] = d > 110 || (res > 28f && k >= 0.88f)
        }

        // 3) Lấy thành phần liên thông lớn nhất (lân cận 5x5 để vượt qua khe giữa các ô)
        val label = IntArray(gw * gh)
        val stack = IntArray(gw * gh)
        var cur = 0
        var bestLabel = 0
        var bestSize = 0
        for (start in 0 until gw * gh) {
            if (!mask[start] || label[start] != 0) continue
            cur++
            var sp = 0
            stack[sp++] = start
            label[start] = cur
            var n = 0
            while (sp > 0) {
                val idx = stack[--sp]
                n++
                val a = idx / gw
                val b = idx % gw
                for (da in -2..2) for (db in -2..2) {
                    val aa = a + da
                    val bb = b + db
                    if (aa in 0 until gh && bb in 0 until gw) {
                        val k = aa * gw + bb
                        if (mask[k] && label[k] == 0) { label[k] = cur; stack[sp++] = k }
                    }
                }
            }
            if (n > bestSize) { bestSize = n; bestLabel = cur }
        }
        if (bestSize < 8) return emptyList()

        var minI = Int.MAX_VALUE
        var minJ = Int.MAX_VALUE
        var maxI = Int.MIN_VALUE
        var maxJ = Int.MIN_VALUE
        for (j in 0 until gh) for (i in 0 until gw) {
            if (label[j * gw + i] == bestLabel) {
                if (i < minI) minI = i
                if (i > maxI) maxI = i
                if (j < minJ) minJ = j
                if (j > maxJ) maxJ = j
            }
        }
        val wg = (maxI - minI + 1).toFloat()
        val hg = (maxJ - minJ + 1).toFloat()

        // 4) Thử mọi lưới n x m (tối đa 5x5), chọn lưới hợp lệ và gần cỡ ô dự kiến nhất
        var best: Cells = emptyList()
        var bestScore = Float.MAX_VALUE
        for (n in 1..5) for (m in 1..5) {
            val sxg = wg / n
            val syg = hg / m
            if (abs(sxg - syg) > 0.25f * max(sxg, syg)) continue
            val sg = (sxg + syg) / 2f
            val hr = (0.25f * sg).toInt().coerceAtLeast(1)
            val cells = ArrayList<Pair<Int, Int>>()
            for (i in 0 until m) for (j in 0 until n) {
                val ci = (minI + (j + 0.5f) * sxg).toInt()
                val cj = (minJ + (i + 0.5f) * syg).toInt()
                var tot = 0
                var hit = 0
                for (a in (cj - hr)..(cj + hr)) for (b in (ci - hr)..(ci + hr)) {
                    if (a in 0 until gh && b in 0 until gw) {
                        tot++
                        if (label[a * gw + b] == bestLabel) hit++
                    }
                }
                if (tot > 0 && hit * 2 >= tot) cells.add(i to j)
            }
            if (cells.isEmpty()) continue
            if (cells.map { it.first }.toSet().size != m || cells.map { it.second }.toSet().size != n) continue
            if (!connected(cells)) continue
            val norm = Solver.normalize(cells)
            var score = abs(sg * step - prior) / prior + 2f * abs(sxg - syg) / max(sxg, syg)
            if (norm !in Solver.KNOWN) score += 0.6f
            if (score < bestScore) { bestScore = score; best = norm }
        }
        return best
    }

    private fun colorDist(a: IntArray, b: IntArray): Int =
        abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    private fun connected(cells: List<Pair<Int, Int>>): Boolean {
        val set = cells.toHashSet()
        val seen = HashSet<Pair<Int, Int>>()
        val stack = ArrayList<Pair<Int, Int>>()
        stack.add(cells[0]); seen.add(cells[0])
        while (stack.isNotEmpty()) {
            val (r, c) = stack.removeAt(stack.size - 1)
            for ((dr, dc) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val q = (r + dr) to (c + dc)
                if (q in set && seen.add(q)) stack.add(q)
            }
        }
        return seen.size == set.size
    }
}
