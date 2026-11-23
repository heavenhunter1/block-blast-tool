package com.huy.blockblast

typealias Cells = List<Pair<Int, Int>>

data class Move(val piece: Int, val r: Int, val c: Int)
data class Result(val placed: Int, val score: Double, val moves: List<Move>)
class Placement(val r: Int, val c: Int, val mask: Long, val lines: LongArray)

/** Bản port của solver Python: bitboard 64 bit (bit = hàng * 8 + cột). */
object Solver {
    const val N = 8
    val ROWS = LongArray(N) { r -> var m = 0L; for (c in 0 until N) m = m or (1L shl (r * N + c)); m }
    val COLS = LongArray(N) { c -> var m = 0L; for (r in 0 until N) m = m or (1L shl (r * N + c)); m }
    private val NOT_COL0 = COLS[0].inv()
    private val NOT_COL7 = COLS[N - 1].inv()
    private val LOW56 = (1L shl 56) - 1
    private const val SURV_W = 3.0

    private val PRESETS: List<List<String>> = listOf(
        listOf("#"), listOf("##"), listOf("#", "#"), listOf("###"), listOf("#", "#", "#"),
        listOf("####"), listOf("#", "#", "#", "#"), listOf("#####"), listOf("#", "#", "#", "#", "#"),
        listOf("##", "##"), listOf("###", "###", "###"), listOf("###", "###"), listOf("##", "##", "##"),
        listOf("#.", "##"), listOf(".#", "##"), listOf("##", "#."), listOf("##", ".#"),
        listOf("#..", "#..", "###"), listOf("..#", "..#", "###"), listOf("###", "#..", "#.."), listOf("###", "..#", "..#"),
        listOf("#.", "#.", "##"), listOf(".#", ".#", "##"), listOf("##", "#.", "#."), listOf("##", ".#", ".#"),
        listOf("###", "#.."), listOf("###", "..#"), listOf("#..", "###"), listOf("..#", "###"),
        listOf(".#.", "###"), listOf("###", ".#."), listOf(".#", "##", ".#"), listOf("#.", "##", "#."),
        listOf("##.", ".##"), listOf(".##", "##."), listOf(".#", "##", "#."), listOf("#.", "##", ".#")
    )

    fun normalize(cells: Cells): Cells {
        if (cells.isEmpty()) return cells
        val mr = cells.minOf { it.first }
        val mc = cells.minOf { it.second }
        return cells.map { (it.first - mr) to (it.second - mc) }
            .sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
    }

    private fun parse(rows: List<String>): Cells {
        val out = ArrayList<Pair<Int, Int>>()
        for ((r, row) in rows.withIndex()) for ((c, ch) in row.withIndex()) if (ch == '#') out.add(r to c)
        return normalize(out)
    }

    fun placements(cells: Cells): List<Placement> {
        val h = cells.maxOf { it.first } + 1
        val w = cells.maxOf { it.second } + 1
        val out = ArrayList<Placement>()
        for (r in 0..N - h) for (c in 0..N - w) {
            var m = 0L
            for ((dr, dc) in cells) m = m or (1L shl ((r + dr) * N + c + dc))
            val lines = LongArray(h + w)
            for (i in 0 until h) lines[i] = ROWS[r + i]
            for (j in 0 until w) lines[h + j] = COLS[c + j]
            out.add(Placement(r, c, m, lines))
        }
        return out
    }

    val KNOWN: Set<Cells> = PRESETS.map { parse(it) }.toSet()
    private val SHAPES: List<Pair<Double, List<Long>>> =
        PRESETS.map { val cells = parse(it); (cells.size / 4.0) to placements(cells).map { p -> p.mask } }

    private fun bc(x: Long) = java.lang.Long.bitCount(x)
    private fun lineScore(n: Int) = if (n == 0) 0 else 80 * n + 40 * n * (n - 1)

    private val evalCache = HashMap<Long, Double>()

    private fun evaluate(board: Long): Double {
        evalCache[board]?.let { return it }
        val free = board.inv()
        val trans = bc((board xor (board ushr 1)) and NOT_COL7) + bc((board xor (board ushr N)) and LOW56)
        val nb = ((free ushr 1) and NOT_COL7) or ((free shl 1) and NOT_COL0) or (free ushr N) or (free shl N)
        val holes = bc(free and nb.inv())
        val v = -(1.5 * trans + 10.0 * holes + 0.6 * bc(board))
        evalCache[board] = v
        return v
    }

    private fun survival(board: Long): Double {
        var s = 0.0
        for ((w, masks) in SHAPES) {
            for (m in masks) if ((board and m) == 0L) { s += w; break }
        }
        return s
    }

    /** Số đường xoá được ở từng nước đi (để hiển thị). */
    fun simulate(board: Long, pieces: List<Cells>, moves: List<Move>): List<Int> {
        var b = board
        val out = ArrayList<Int>()
        for (mv in moves) {
            var m = 0L
            for ((dr, dc) in pieces[mv.piece]) m = m or (1L shl ((mv.r + dr) * N + mv.c + dc))
            b = b or m
            var clr = 0L
            var cnt = 0
            for (i in 0 until N) {
                if ((b and ROWS[i]) == ROWS[i]) { clr = clr or ROWS[i]; cnt++ }
                if ((b and COLS[i]) == COLS[i]) { clr = clr or COLS[i]; cnt++ }
            }
            b = b and clr.inv()
            out.add(cnt)
        }
        return out
    }

    /** pieces: đúng 3 phần tử, rỗng nếu ô khối đó không có khối. */
    fun solve(board: Long, pieces: List<Cells>, topK: Int = 60): Result {
        val pls = pieces.map { if (it.isEmpty()) emptyList() else placements(it) }
        var idx = 0
        for (i in pieces.indices) if (pieces[i].isNotEmpty()) idx = idx or (1 shl i)

        val leaves = HashMap<Long, Triple<Int, Int, List<Move>>>()
        val seen = HashMap<Pair<Long, Int>, Int>()

        fun dfs(b: Long, remaining: Int, placed: Int, ls: Int, moves: List<Move>) {
            val key = Pair(b, remaining)
            val prev = seen[key]
            if (prev != null && prev >= ls) return
            seen[key] = ls
            var moved = false
            val tried = ArrayList<Cells>()
            for (i in 0 until 3) {
                if ((remaining and (1 shl i)) == 0) continue
                if (tried.contains(pieces[i])) continue
                tried.add(pieces[i])
                val rest = remaining and (1 shl i).inv()
                for (p in pls[i]) {
                    if ((b and p.mask) != 0L) continue
                    moved = true
                    var nb = b or p.mask
                    var clr = 0L
                    var cnt = 0
                    for (lm in p.lines) if ((nb and lm) == lm) { clr = clr or lm; cnt++ }
                    if (clr != 0L) nb = nb and clr.inv()
                    dfs(nb, rest, placed + 1, ls + lineScore(cnt), moves + Move(i, p.r, p.c))
                }
            }
            if (!moved) {
                val old = leaves[b]
                if (old == null || placed > old.first || (placed == old.first && ls > old.second)) {
                    leaves[b] = Triple(placed, ls, moves)
                }
            }
        }
        dfs(board, idx, 0, 0, emptyList())

        val bestPlaced = leaves.values.maxOf { it.first }
        val cands = leaves.entries.filter { it.value.first == bestPlaced }
            .sortedByDescending { it.value.second + evaluate(it.key) }
        var bestScore = Double.NEGATIVE_INFINITY
        var bestMoves: List<Move> = emptyList()
        for (e in cands.take(topK)) {
            val s = e.value.second + evaluate(e.key) + SURV_W * survival(e.key)
            if (s > bestScore) { bestScore = s; bestMoves = e.value.third }
        }
        return if (bestPlaced == 0) Result(0, bestScore - 1000, emptyList())
        else Result(bestPlaced, bestScore, bestMoves)
    }
}
