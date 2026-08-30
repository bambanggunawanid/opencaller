package dev.opencaller.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Truecaller-style large on-screen badge: a red card drawn over
 * whatever is on screen while a flagged call rings (opt-in, needs the
 * "Display over other apps" special access). Views are built in code —
 * overlays live outside the Compose activity.
 *
 * Auto-dismisses after [AUTO_DISMISS_MS] or on tap; only one card at a
 * time (a newer verdict replaces the current one).
 */
object OverlayWarning {
  private const val AUTO_DISMISS_MS = 20_000L

  private val main = Handler(Looper.getMainLooper())
  private var current: View? = null

  fun show(context: Context, title: String, body: String) {
    val app = context.applicationContext
    if (!Prefs.overlayEnabled(app) || !Settings.canDrawOverlays(app)) return
    main.post {
      dismiss(app)
      val wm = app.getSystemService(WindowManager::class.java) ?: return@post
      val view = build(app, title, body)
      val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP
        y = (64 * app.resources.displayMetrics.density).toInt()
      }
      try {
        wm.addView(view, lp)
      } catch (_: Exception) {
        return@post // permission revoked mid-flight etc.
      }
      current = view
      view.setOnClickListener { dismiss(app) }
      main.postDelayed({ if (current === view) dismiss(app) }, AUTO_DISMISS_MS)
    }
  }

  fun dismiss(context: Context) {
    val view = current ?: return
    current = null
    runCatching {
      context.applicationContext
        .getSystemService(WindowManager::class.java)
        ?.removeView(view)
    }
  }

  private fun build(context: Context, title: String, body: String): View {
    val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(16), dp(20), dp(14))
      background = GradientDrawable().apply {
        cornerRadius = 20f * density
        setColor(0xFFB3261E.toInt())
      }
      elevation = 10f * density
    }
    card.addView(TextView(context).apply {
      text = title
      setTextColor(Color.WHITE)
      textSize = 22f
      setTypeface(typeface, Typeface.BOLD)
    })
    card.addView(TextView(context).apply {
      text = body
      setTextColor(Color.WHITE)
      textSize = 16f
      setPadding(0, dp(4), 0, 0)
    })
    card.addView(TextView(context).apply {
      text = "OpenCaller • tap to dismiss"
      setTextColor(0xCCFFFFFF.toInt())
      textSize = 12f
      setPadding(0, dp(8), 0, 0)
    })

    return FrameLayout(context).apply {
      setPadding(dp(12), 0, dp(12), 0)
      addView(card)
    }
  }
}
