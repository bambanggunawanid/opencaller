package dev.opencaller.app

/**
 * F5 user rules: personal allow/block lists with prefix support.
 *
 * Rule format after normalization: a digit string, optionally ending in
 * `*` for a prefix rule — "+1 (800) *" becomes "1800*". Matching is
 * digit-wise and also tries the 1-prefixed NANP candidate, mirroring the
 * lookup shim, so "800-555-0100" and "+1 800 555 0100" behave the same.
 *
 * Precedence (applied by ScreeningService): user ALLOW beats everything —
 * a user must always be able to whitelist their pharmacy even if the DB
 * flags it; then user BLOCK; then DB; then heuristics; then unknown-mode.
 * Pure functions; storage lives in Prefs.
 */
object RuleEngine {
  enum class Verdict { ALLOW, BLOCK }

  /** Normalize user input to storage form; null if it has no digits. */
  fun normalizeRule(raw: String): String? {
    val isPrefix = raw.trim().endsWith("*")
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    return if (isPrefix) "$digits*" else digits
  }

  fun evaluate(
    number: String,
    allowRules: Set<String>,
    blockRules: Set<String>,
  ): Verdict? {
    val digits = number.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    val nanp = if (digits.length == 10) "1$digits" else digits
    if (allowRules.any { matches(it, digits, nanp) }) return Verdict.ALLOW
    if (blockRules.any { matches(it, digits, nanp) }) return Verdict.BLOCK
    return null
  }

  private fun matches(rule: String, digits: String, nanp: String): Boolean {
    val body = rule.removeSuffix("*")
    return if (rule.endsWith("*")) {
      digits.startsWith(body) || nanp.startsWith(body)
    } else {
      digits == body || nanp == body
    }
  }
}
