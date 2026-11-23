package com.huy.blockblast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqProjection = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = Prefs(this)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(s: String, size: Float = 15f) = TextView(this).apply { text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 2) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }

        root.addView(text("Block Blast Overlay", 24f))
        root.addView(text(
            "1) Cấp quyền hiển thị trên ứng dụng khác.\n" +
            "2) Bấm 'Bắt đầu', cho phép ghi màn hình rồi mở Block Blast.\n" +
            "3) Lần đầu: bấm ⚙ trên thanh nổi để khớp bàn cờ và khay khối.\n" +
            "4) Mỗi lượt mới có 3 khối: bấm 💡 để xem gợi ý, 🙈 để ẩn, ✕ để tắt."
        ))
        root.addView(button("Bước 1: Cấp quyền overlay") {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Đã có quyền overlay", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        val ratioLabel = text("")
        fun updateLabel(p: Int) { ratioLabel.text = "Cỡ ô của khối trong khay so với ô bàn cờ: $p%" }
        val seek = SeekBar(this).apply {
            max = 50 // 30%..80%
            progress = (prefs.ratio * 100).toInt() - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    updateLabel(value + 30)
                    if (fromUser) prefs.ratio = (value + 30) / 100f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        updateLabel(seek.progress + 30)
        root.addView(ratioLabel)
        root.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(text("(Nếu khối nhận diện sai kích thước, chỉnh thanh này. Mặc định 50%.)", 12f))

        root.addView(button("Bước 2: Bắt đầu overlay") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Hãy cấp quyền overlay trước", Toast.LENGTH_LONG).show()
            } else {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
