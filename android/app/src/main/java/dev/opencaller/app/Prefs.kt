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
    "sms-spam",
  )

  /** Large on-screen overlay badge during flagged calls (opt-in). */
  fun overlayEnabled(context: Context): Boolean =
    prefs(context).getBoolean("overlay_warning", false)

  fun setOverlayEnabled(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean("overlay_warning", on).apply()
  }

  /** F-SMS: what the notification listener does with spam texts. */
  enum class SmsMode { OFF, WARN, MUTE }

  fun smsMode(context: Context): SmsMode {
    val raw = prefs(context).getString("sms_mode", null) ?: return SmsMode.OFF
    return runCatching { SmsMode.valueOf(raw) }.getOrDefault(SmsMode.OFF)
  }

  fun setSmsMode(context: Context, mode: SmsMode) {
    prefs(context).edit().putString("sms_mode", mode.name).apply()
  }

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

  // ---- F3 country shards ----

  /** Countries with a published shard (mirror of the CI matrix). */
  val AVAILABLE_SHARDS = setOf("us")

  /** SIM country (ISO 3166-1 alpha-2, lowercase) — no permission needed. */
  fun simCountry(context: Context): String =
    context.getSystemService(android.telephony.TelephonyManager::class.java)
      ?.simCountryIso?.lowercase().orEmpty()

  /**
   * Enabled shard countries. First call defaults to the SIM country when a
   * shard exists for it, else "us", and persists the choice.
   */
  fun enabledCountries(context: Context): Set<String> {
    prefs(context).getStringSet("countries.enabled", null)?.let { stored ->
      return stored.filter { it in AVAILABLE_SHARDS }.toSet()
    }
    val sim = simCountry(context)
    val default = if (sim in AVAILABLE_SHARDS) setOf(sim) else setOf("us")
    prefs(context).edit().putStringSet("countries.enabled", default).apply()
    return default
  }

  fun setCountryEnabled(context: Context, country: String, enabled: Boolean) {
    val set = enabledCountries(context).toMutableSet()
    if (enabled) set.add(country) else set.remove(country)
    prefs(context).edit().putStringSet("countries.enabled", set).apply()
  }

  // ---- F5 user rules (see RuleEngine for format and precedence) ----

  fun allowRules(context: Context): Set<String> =
    prefs(context).getStringSet("rules.allow", emptySet()) ?: emptySet()

  fun blockRules(context: Context): Set<String> =
    prefs(context).getStringSet("rules.block", emptySet()) ?: emptySet()

  /** Returns false if the rule normalizes to nothing. */
  fun addRule(context: Context, block: Boolean, raw: String): Boolean {
    val rule = RuleEngine.normalizeRule(raw) ?: return false
    val key = if (block) "rules.block" else "rules.allow"
    val set = (prefs(context).getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
    set.add(rule)
    prefs(context).edit().putStringSet(key, set).apply()
    return true
  }

  fun removeRule(context: Context, block: Boolean, rule: String) {
    val key = if (block) "rules.block" else "rules.allow"
    val set = (prefs(context).getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
    set.remove(rule)
    prefs(context).edit().putStringSet(key, set).apply()
  }

  /** F5 "silence all unknown numbers" mode. Off by default. */
  fun silenceUnknown(context: Context): Boolean =
    prefs(context).getBoolean("silence_unknown", false)

  fun setSilenceUnknown(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean("silence_unknown", on).apply()
  }

  /**
   * "Expecting a call": pauses silence-unknown and local-learning verdicts
   * until this time — for the courier/driver moments where unknown numbers
   * are wanted. DB and rule verdicts are never paused.
   */
  fun pausedUntil(context: Context): Long = prefs(context).getLong("pause_until", 0)

  fun setPausedUntil(context: Context, untilMillis: Long) {
    prefs(context).edit().putLong("pause_until", untilMillis).apply()
  }

  /** Local behavioral learning from missed-call patterns (wangiri watch). */
  fun wangiriEnabled(context: Context): Boolean =
    prefs(context).getBoolean("wangiri_learn", true)

  fun setWangiriEnabled(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean("wangiri_learn", on).apply()
  }

  /** UI language override: "" = follow system, else a BCP-47 tag. */
  fun language(context: Context): String =
    prefs(context).getString("language", "") ?: ""

  fun setLanguage(context: Context, tag: String) {
    prefs(context).edit().putString("language", tag).apply()
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
