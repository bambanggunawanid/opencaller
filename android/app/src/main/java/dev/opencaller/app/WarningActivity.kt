package dev.opencaller.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen spam warning for the screen-off/locked case: overlays cannot
 * draw over the keyguard and heads-up banners don't peek on a dark screen,
 * so — like any calling app — we use a full-screen intent that turns the
 * screen on and shows over the lock screen. Fired by Notifier only when
 * the large-badge setting is on; when the device is unlocked and in use,
 * Android suppresses this in favor of the heads-up + overlay.
 *
 * Plain views on purpose: this must be lightweight and instant.
 */
class WarningActivity : Activity() {

  override fun attachBaseContext(newBase: android.content.Context) {
    super.attachBaseContext(L10n.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(0xFFB3261E.toInt())
      setPadding(dp(32), dp(32), dp(32), dp(32))
    }
    root.addView(TextView(this).apply {
      text = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.overlay_suspicious_call)
      setTextColor(Color.WHITE)
      textSize = 30f
      gravity = Gravity.CENTER
      setTypeface(typeface, Typeface.BOLD)
    })
    root.addView(TextView(this).apply {
      text = intent.getStringExtra(EXTRA_BODY) ?: ""
      setTextColor(Color.WHITE)
      textSize = 20f
      gravity = Gravity.CENTER
      setPadding(0, dp(16), 0, 0)
    })
    root.addView(TextView(this).apply {
      text = getString(R.string.warning_footer)
      setTextColor(0xCCFFFFFF.toInt())
      textSize = 13f
      gravity = Gravity.CENTER
      setPadding(0, dp(28), 0, 0)
    })
    root.setOnClickListener { finish() }
    setContentView(root)

    Handler(Looper.getMainLooper()).postDelayed(
      { if (!isFinishing) finish() },
      AUTO_DISMISS_MS,
    )
  }

  companion object {
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    private const val AUTO_DISMISS_MS = 25_000L
  }
}
