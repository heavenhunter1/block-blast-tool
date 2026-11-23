package com.huy.blockblast

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("bb", Context.MODE_PRIVATE)

    /** Tỉ lệ cỡ ô của khối trong khay so với ô trên bàn cờ. */
    var ratio: Float
        get() = sp.getFloat("ratio", 0.5f)
        set(v) { sp.edit().putFloat("ratio", v).apply() }

    fun loadGeo(w: Int, h: Int): Geo {
        val by0 = sp.getFloat("by0", 0.25f)
        return Geo(
            sp.getFloat("bx0", 0.04f) * w, by0 * h,
            sp.getFloat("bx1", 0.96f) * w, sp.getFloat("by1", by0 + 0.92f * w / h) * h,
            sp.getFloat("tx0", 0.04f) * w, sp.getFloat("ty0", 0.72f) * h,
            sp.getFloat("tx1", 0.96f) * w, sp.getFloat("ty1", 0.88f) * h
        )
    }

    fun saveGeo(g: Geo, w: Int, h: Int) {
        sp.edit()
            .putFloat("bx0", g.bx0 / w).putFloat("by0", g.by0 / h)
            .putFloat("bx1", g.bx1 / w).putFloat("by1", g.by1 / h)
            .putFloat("tx0", g.tx0 / w).putFloat("ty0", g.ty0 / h)
            .putFloat("tx1", g.tx1 / w).putFloat("ty1", g.ty1 / h)
            .apply()
    }
}
