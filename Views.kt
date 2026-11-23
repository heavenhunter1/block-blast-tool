package com.huy.blockblast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

private val PIECE_COLORS = intArrayOf(0xFFEF476F.toInt(), 0xFF06D6A0.toInt(), 0xFF118AB2.toInt())

/** Vẽ gợi ý đè lên bàn cờ (không nhận cảm ứng, chạm xuyên xuống game). */
class SuggestionView(
    ctx: Context,
    private val geo: Geo,
    private val det: Detection,
    private val res: Result,
    private val cleared: List<Int>
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * d
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val panel = Paint().apply { color = 0xD0101020.toInt() }
    private val left = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * d }

    override fun onDraw(c: Canvas) {
        val cw = (geo.bx1 - geo.bx0) / 8f
        val ch = (geo.by1 - geo.by0) / 8f

        // 1) Ô gợi ý trên bàn cờ
        txt.textSize = minOf(cw, ch) * 0.5f
        for ((k, mv) in res.moves.withIndex()) {
            fill.color = (PIECE_COLORS[mv.piece] and 0x00FFFFFF) or 0xB0000000.toInt()
            for ((dr, dc) in det.pieces[mv.piece]) {
                val x = geo.bx0 + (mv.c + dc) * cw
                val y = geo.by0 + (mv.r + dr) * ch
                val rect = RectF(x + 2, y + 2, x + cw - 2, y + ch - 2)
                c.drawRoundRect(rect, 8f, 8f, fill)
                c.drawRoundRect(rect, 8f, 8f, stroke)
            }
            val (dr0, dc0) = det.pieces[mv.piece][0]
            val nx = geo.bx0 + (mv.c + dc0 + 0.5f) * cw
            val ny = geo.by0 + (mv.r + dr0 + 0.5f) * ch
            c.drawText("${k + 1}", nx, ny + txt.textSize / 3f, txt)
        }

        // 2) Khung quanh khối trong khay + số thứ tự đặt
        val slotW = (geo.tx1 - geo.tx0) / 3f
        txt.textSize = 22 * d
        for ((k, mv) in res.moves.withIndex()) {
            stroke.color = PIECE_COLORS[mv.piece]
            val x0 = geo.tx0 + mv.piece * slotW
            c.drawRoundRect(RectF(x0 + 4, geo.ty0, x0 + slotW - 4, geo.ty1), 16f, 16f, stroke)
            c.drawText("${k + 1}", x0 + slotW / 2f, geo.ty0 - 6 * d, txt)
        }
        stroke.color = Color.WHITE

        // 3) Bảng thông tin ở đầu màn hình
        val lines = ArrayList<String>()
        if (res.moves.isEmpty()) {
            lines.add("Không đặt được khối nào - hết nước đi!")
        } else {
            for ((k, mv) in res.moves.withIndex()) {
                val extra = if (cleared[k] > 0) "  ✦ xoá ${cleared[k]} đường" else ""
                lines.add("${k + 1}. Khối ${mv.piece + 1} → hàng ${mv.r + 1}, cột ${mv.c + 1}$extra")
            }
            val total = det.pieces.count { it.isNotEmpty() }
            if (res.placed < total) lines.add("⚠ Chỉ đặt được ${res.placed}/$total khối!")
        }
        val top = 40 * d
        val lineH = 20 * d
        val miniCell = 8 * d
        val panelH = 12 * d + lines.size * lineH + 5 * miniCell + 24 * d
        c.drawRect(0f, top, width.toFloat(), top + panelH, panel)
        var y = top + 12 * d + 14 * d
        for (s in lines) { c.drawText(s, 12 * d, y, left); y += lineH }

        // 4) Khối đã nhận diện (để kiểm tra nhanh)
        left.textSize = 12 * d
        c.drawText("Nhận diện:", 12 * d, y + 8 * d, left)
        left.textSize = 14 * d
        for ((i, cells) in det.pieces.withIndex()) {
            val ox = 100 * d + i * 70 * d
            fill.color = PIECE_COLORS[i]
            for ((r, cc) in cells) {
                c.drawRect(ox + cc * miniCell, y + r * miniCell, ox + (cc + 1) * miniCell - 1, y + (r + 1) * miniCell - 1, fill)
            }
        }
    }
}

/** Cho người dùng kéo 4 góc để khớp bàn cờ + khay khối. */
class CalibrationView(
    ctx: Context,
    private val sw: Int,
    private val sh: Int,
    g: Geo,
    private val onDone: (Geo?) -> Unit
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var bx0 = g.bx0; private var by0 = g.by0; private var bx1 = g.bx1; private var by1 = g.by1
    private var tx0 = g.tx0; private var ty0 = g.ty0; private var tx1 = g.tx1; private var ty1 = g.ty1
    private var active = -1

    private val pBoard = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF00E676.toInt(); strokeWidth = 3 * d }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x8800E676.toInt(); strokeWidth = 1 * d }
    private val pTray = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFF9100.toInt(); strokeWidth = 3 * d }
    private val pHandleB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt() }
    private val pHandleT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9100.toInt() }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2962FF.toInt() }
    private val pBtn2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF616161.toInt() }
    private val pBg = Paint().apply { color = 0xB0000000.toInt() }

    private val saveRect get() = RectF(sw * 0.08f, sh - 130 * d, sw * 0.48f, sh - 70 * d)
    private val cancelRect get() = RectF(sw * 0.52f, sh - 130 * d, sw * 0.92f, sh - 70 * d)

    private fun handle(i: Int): Pair<Float, Float> = when (i) {
        0 -> bx0 to by0
        1 -> bx1 to by1
        2 -> tx0 to ty0
        else -> tx1 to ty1
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0x33000000)
        // bàn cờ + lưới 8x8
        c.drawRect(bx0, by0, bx1, by1, pBoard)
        for (i in 1 until 8) {
            val x = bx0 + (bx1 - bx0) * i / 8f
            val y = by0 + (by1 - by0) * i / 8f
            c.drawLine(x, by0, x, by1, pGrid)
            c.drawLine(bx0, y, bx1, y, pGrid)
        }
        // khay 3 khối
        c.drawRect(tx0, ty0, tx1, ty1, pTray)
        for (i in 1 until 3) {
            val x = tx0 + (tx1 - tx0) * i / 3f
            c.drawLine(x, ty0, x, ty1, pGrid)
        }
        for (i in 0..3) {
            val (x, y) = handle(i)
            c.drawCircle(x, y, 14 * d, if (i < 2) pHandleB else pHandleT)
        }
        // hướng dẫn
        c.drawRect(0f, 30 * d, sw.toFloat(), 130 * d, pBg)
        c.drawText("Kéo 2 góc XANH LÁ cho khớp bàn cờ 8x8", sw / 2f, 60 * d, pText)
        c.drawText("Kéo 2 góc CAM cho khớp khay 3 khối (chia 3 ô đều)", sw / 2f, 85 * d, pText)
        c.drawText("Mỗi ô lưới phải trùng đúng ô của game rồi bấm Lưu", sw / 2f, 110 * d, pText)
        c.drawRoundRect(saveRect, 16f, 16f, pBtn)
        c.drawRoundRect(cancelRect, 16f, 16f, pBtn2)
        c.drawText("LƯU", saveRect.centerX(), saveRect.centerY() + 6 * d, pText)
        c.drawText("HUỶ", cancelRect.centerX(), cancelRect.centerY() + 6 * d, pText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (saveRect.contains(x, y)) { onDone(Geo(bx0, by0, bx1, by1, tx0, ty0, tx1, ty1)); return true }
                if (cancelRect.contains(x, y)) { onDone(null); return true }
                active = -1
                var bestD = 90 * d
                for (i in 0..3) {
                    val (hx, hy) = handle(i)
                    val dist = abs(hx - x) + abs(hy - y)
                    if (dist < bestD) { bestD = dist; active = i }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val px = x.coerceIn(0f, sw.toFloat())
                val py = y.coerceIn(0f, sh.toFloat())
                when (active) {
                    0 -> { bx0 = px; by0 = py }
                    1 -> { bx1 = px; by1 = py }
                    2 -> { tx0 = px; ty0 = py }
                    3 -> { tx1 = px; ty1 = py }
                }
                if (active >= 0) invalidate()
            }
            MotionEvent.ACTION_UP -> active = -1
        }
        return true
    }
}
