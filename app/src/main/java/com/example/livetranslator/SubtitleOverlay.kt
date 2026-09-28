package com.example.livetranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 다른 앱(브라우저·유튜브) 위에 떠 있는 자막 창. 위아래로 끌어서 옮길 수 있음 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class SubtitleOverlay(private val ctx: Context, private val onClose: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val original = TextView(ctx).apply {
        setTextColor(0xFFBBBBBB.toInt()); textSize = 13f; gravity = Gravity.CENTER
    }
    private val translated = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
    }
    private val closeBtn = TextView(ctx).apply {
        text = "✕"; setTextColor(Color.WHITE); textSize = 16f
        setPadding(dp(10), dp(2), dp(10), dp(2))
        setOnClickListener { onClose() }
    }
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt()); cornerRadius = dp(12).toFloat()
        }
        addView(closeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END })
        addView(original, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(translated, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP; y = dp(420) }
    private var shown = false

    init {
        var downY = 0f
        var startY = 0
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(root, params)
                }
            }
            true
        }
    }

    fun show() { if (!shown) { wm.addView(root, params); shown = true } }
    fun remove() { if (shown) { runCatching { wm.removeView(root) }; shown = false } }
    fun setOriginal(t: String) { original.text = t }
    fun setTranslated(t: String) { translated.text = t }
    fun setText(orig: String, tr: String) { original.text = orig; translated.text = tr }
}
