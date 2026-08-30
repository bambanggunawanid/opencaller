package dev.opencaller.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Local settings (F2/F4). Plain SharedPreferences in app-private storage —
 * like everything else in this app, settings never leave the device.
 */
object Prefs {
  /** What the screening service does with a matched call. */
  enum class Action { ALLOW, SILENCE, REJECT }

  /** F4 schedule: when the weekly shard update may run. */
  enum class UpdateMode { OFF, WIFI_ONLY, ANY_NETWORK }

  /** Order matches core's Category severity ordering. */
  val CATEGORIES = listOf(
    "scam", "robocall", "telemarketing", "debt-collection", "survey", "other",
  )

  /** Pseudo-category for F7 heuristic verdicts (spoofing/invalid numbers). */
  const val HEURISTIC = "suspicious"

  // PRD §6 F2 defaults: silence what's confidently hostile, allow-with-
  // warning for the rest. Heuristic hits default to silence too.
  private val DEFAULT_SILENCED = setOf("scam", "robocall", HEURISTIC)

  private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  fun action(context: Context, category: String): Action {
    val default = if (category in DEFAULT_SILENCED) Action.SILENCE else Action.ALLOW
    val raw = prefs(context).getString("action.$category", null) ?: return default
    return runCatching { Action.valueOf(raw) }.getOrDefault(default)
  }

  fun setAction(context: Context, category: String, action: Action) {
    prefs(context).edit().putString("action.$category", action.name).apply()
  }

  /**
   * User's own number for neighbor-spoof detection (F7). Deliberately
   * user-entered rather than read from the SIM: no READ_PHONE_NUMBERS
   * permission, and the feature is plainly opt-in. Empty = off.
   */
  fun ownNumber(context: Context): String =
    prefs(context).getString("own_number", "") ?: ""

  fun setOwnNumber(context: Context, number: String) {
    prefs(context).edit().putString("own_number", number).apply()
  }

  fun updateMode(context: Context): UpdateMode {
    val raw = prefs(context).getString("update_mode", null)
      ?: return UpdateMode.WIFI_ONLY
    return runCatching { UpdateMode.valueOf(raw) }.getOrDefault(UpdateMode.WIFI_ONLY)
  }

  fun setUpdateMode(context: Context, mode: UpdateMode) {
    prefs(context).edit().putString("update_mode", mode.name).apply()
  }
}
