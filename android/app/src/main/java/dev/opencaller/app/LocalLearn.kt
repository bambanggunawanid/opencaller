package dev.opencaller.app

import android.content.Context

/**
 * On-device behavioral learning — the defense that gets FASTER than a
 * spammer rotating fresh SIMs. Signal: an unknown number rings and is
 * missed within seconds ("wangiri" bait / burner-blast pattern). Each such
 * event strikes the caller's thousand-number prefix block; two strikes
 * within [WINDOW_MS] mark the block locally suspicious, and further calls
 * from it are handled like heuristic hits.
 *
 * Entirely local: learned prefixes live in app-private prefs, decay after
 * 48 h, and never leave the phone. One strike is never enough — a single
 * missed courier call cannot condemn a block.
 */
object LocalLearn {
  private const val FILE = "local_learn"
  private const val WINDOW_MS = 48L * 60 * 60 * 1000
  private const val STRIKES_TO_FLAG = 2

  private fun prefs(context: Context) =
    context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** Thousand-block prefix for a digit string (needs >= 7 digits). */
  fun blockPrefix(digits: String): String? =
    if (digits.length >= 7) digits.dropLast(3) else null

  /** Record a wangiri-style event for this caller. Returns strike count. */
  fun strike(context: Context, digits: String): Int {
    val prefix = blockPrefix(digits) ?: return 0
    val p = prefs(context)
    prune(context)
    val now = System.currentTimeMillis()
    val strikes = (p.getString(prefix, null)?.substringBefore(',')?.toIntOrNull() ?: 0) + 1
    p.edit().putString(prefix, "$strikes,$now").apply()
    return strikes
  }

  fun isSuspicious(context: Context, digits: String): Boolean {
    val prefix = blockPrefix(digits) ?: return false
    val raw = prefs(context).getString(prefix, null) ?: return false
    val strikes = raw.substringBefore(',').toIntOrNull() ?: return false
    val at = raw.substringAfter(',').toLongOrNull() ?: return false
    return strikes >= STRIKES_TO_FLAG &&
      System.currentTimeMillis() - at <= WINDOW_MS
  }

  private fun prune(context: Context) {
    val p = prefs(context)
    val now = System.currentTimeMillis()
    val editor = p.edit()
    for ((key, value) in p.all) {
      val at = (value as? String)?.substringAfter(',')?.toLongOrNull() ?: 0
      if (now - at > WINDOW_MS) editor.remove(key)
    }
    editor.apply()
  }
}
