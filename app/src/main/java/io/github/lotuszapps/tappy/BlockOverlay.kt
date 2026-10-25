package io.github.lotuszapps.tappy

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen message drawn by the accessibility service itself. Unlike an activity it can't
 * be refused by Android's background-start limits or covered by the home screen, so it shows
 * every time. A plain message is dismissed with OK or by itself after a few seconds; a question
 * (with [show]'s actions) stays until one of its buttons is tapped or the service hides it.
 */
class BlockOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val autoHide = Runnable { hide() }

    fun show(
        title: String,
        body: String,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        hide()
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF51F1B2E.toInt())
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true // swallow touches so nothing underneath can be used
        }
        root.addView(ImageView(service).apply {
            setImageResource(R.drawable.ic_stat_lock)
            setColorFilter(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(24) })
        root.addView(TextView(service).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(service).apply {
            text = body
            setTextColor(0xDDFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12); bottomMargin = dp(32) })
        val buttons = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        actions.ifEmpty { listOf("OK" to {}) }.forEach { (label, action) ->
            buttons.addView(Button(service).apply {
                text = label
                setOnClickListener {
                    hide()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8); marginEnd = dp(8) })
        }
        root.addView(buttons)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(root, params)
        view = root
        // A question must stay up: hiding it would reveal the app underneath.
        if (actions.isEmpty()) root.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    fun hide() {
        val v = view ?: return
        view = null
        v.removeCallbacks(autoHide)
        runCatching { windowManager.removeView(v) }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTO_HIDE_MS = 8_000L
    }
}
