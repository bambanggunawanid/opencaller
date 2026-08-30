package dev.opencaller.app

/**
 * F5 user rules: personal allow/block lists with prefix support.
 *
 * Two rule kinds, auto-detected from what the user types:
 * - **Number rules** ("+1 800 555 0100", "62812*"): digit-wise match, also
 *   trying the 1-prefixed NANP candidate (mirrors the lookup shim).
 * - **Sender-name rules** ("IM3 Promo", "3Untukmu", "bima+", "promo*"):
 *   case-insensitive match against the sender display string — SMS
 *   gateways use alphanumeric IDs with no number at all.
 * A trailing `*` makes either kind a prefix rule.
 *
 * Precedence (applied by callers): user ALLOW beats everything — a user
 * must always be able to whitelist their pharmacy even if the DB flags
 * it; then user BLOCK; then DB; then heuristics; then unknown-mode.
 * Pure functions; storage lives in Prefs.
 */
object RuleEngine {
  enum class Verdict { ALLOW, BLOCK }

  private const val SEPARATORS = " +-.()"

  /** Normalize user input to storage form; null if empty. */
  fun normalizeRule(raw: String): String? {
    val trimmed = raw.trim()
    val isPrefix = trimmed.endsWith("*")
    val body = trimmed.removeSuffix("*").trim()
    if (body.isEmpty()) return null
    val stripped = body.filter { it !in SEPARATORS }
    val star = if (isPrefix) "*" else ""
    return if (stripped.all { it.isDigit() }) {
      "$stripped$star" // number rule
    } else {
      "${body.lowercase()}$star" // sender-name rule
    }
  }

  /**
   * @param number the caller/sender number in any dialable form ("" if the
   *   sender has no number, e.g. alphanumeric SMS gateways)
   * @param senderName the display string shown for the sender (may be the
   *   number again, a contact name, or an SMS gateway ID)
   */
  fun evaluate(
    number: String,
    senderName: String = "",
    allowRules: Set<String> = emptySet(),
    blockRules: Set<String> = emptySet(),
  ): Verdict? {
    val digits = number.filter { it.isDigit() }
    val nanp = if (digits.length == 10) "1$digits" else digits
    val text = senderName.trim().lowercase()
    if (digits.isEmpty() && text.isEmpty()) return null
    if (allowRules.any { matches(it, digits, nanp, text) }) return Verdict.ALLOW
    if (blockRules.any { matches(it, digits, nanp, text) }) return Verdict.BLOCK
    return null
  }

  private fun matches(rule: String, digits: String, nanp: String, text: String): Boolean {
    val isPrefix = rule.endsWith("*")
    val body = rule.removeSuffix("*")
    if (body.isEmpty()) return false
    return if (body.all { it.isDigit() }) {
      if (digits.isEmpty()) {
        false
      } else if (isPrefix) {
        digits.startsWith(body) || nanp.startsWith(body)
      } else {
        digits == body || nanp == body
      }
    } else {
      if (text.isEmpty()) {
        false
      } else if (isPrefix) {
        text.startsWith(body)
      } else {
        text == body
      }
    }
  }
}
