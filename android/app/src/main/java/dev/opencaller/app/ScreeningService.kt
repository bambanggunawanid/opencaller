package dev.opencaller.app

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * The hot path (PRD F1/F2/F7): the OS hands us a ringing call; we answer
 * from the local database — and when the DB misses, from data-free
 * heuristics (spoofing/invalid-number checks). This method never touches
 * the network.
 *
 * Per-category actions come from settings (F2). Defaults: scam, robocall,
 * and heuristic hits are silenced; everything else is allowed but flagged
 * in the local history.
 */
class ScreeningService : CallScreeningService() {

  override fun onScreenCall(callDetails: Call.Details) {
    val number = callDetails.handle?.schemeSpecificPart
    if (number.isNullOrBlank()) {
      // No caller ID at all. Heuristics can't run without digits; allow and
      // record. ("Silence unknown numbers" mode is F5, not yet built.)
      respondToCall(callDetails, CallResponse.Builder().build())
      return
    }

    // Precedence (F5): user ALLOW > user BLOCK > DB > heuristics > unknown.
    val ruleVerdict = RuleEngine.evaluate(
      number = number,
      allowRules = Prefs.allowRules(this),
      blockRules = Prefs.blockRules(this),
    )
    val hit = if (ruleVerdict == null) DbManager.lookup(this, number) else null

    val (action, detail) = when {
      ruleVerdict == RuleEngine.Verdict.ALLOW -> Prefs.Action.ALLOW to "user-allow"
      ruleVerdict == RuleEngine.Verdict.BLOCK -> Prefs.Action.REJECT to "user-block"
      hit != null ->
        Prefs.action(this, hit.category) to "${hit.category}:${hit.reportCount}"
      else -> {
        val suspicion = NativeCore.nativeHeuristic(
          normalizeCandidate(number),
          Prefs.ownNumber(this),
        )
        when {
          suspicion != null ->
            Prefs.action(this, Prefs.HEURISTIC) to "heuristic:$suspicion"
          Prefs.silenceUnknown(this) -> Prefs.Action.SILENCE to "unknown-mode"
          else -> Prefs.Action.ALLOW to null
        }
      }
    }

    val response = when (action) {
      Prefs.Action.ALLOW -> CallResponse.Builder().build()
      Prefs.Action.SILENCE -> CallResponse.Builder().setSilenceCall(true).build()
      Prefs.Action.REJECT -> CallResponse.Builder()
        .setDisallowCall(true)
        .setRejectCall(true)
        .build()
    }
    respondToCall(callDetails, response)

    // F1 label: show WHY. Skip unknowns (noise), user-allows (already the
    // user's decision), and silence-unknown mode (a mode, not a verdict).
    if (detail != null && detail != "user-allow" && detail != "unknown-mode") {
      Notifier.postVerdict(this, number, action, detail)
      // Large opt-in badge — not for REJECT (the call never rings; the
      // notification is the record).
      if (action != Prefs.Action.REJECT) {
        OverlayWarning.show(
          this,
          if (action == Prefs.Action.SILENCE) "⚠ Spam call silenced" else "⚠ Suspicious call",
          "$number — ${Notifier.friendly(detail)}",
        )
      }
    }

    DbManager.logEvent(
      this,
      number,
      when {
        detail == null -> "unknown"
        action == Prefs.Action.ALLOW -> "allowed:$detail"
        else -> "${action.name.lowercase()}:$detail"
      },
    )
  }

  /** Same US M0 shim as DbManager: 10-digit national → 1-prefixed NANP. */
  private fun normalizeCandidate(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.length == 10) "1$digits" else number
  }
}
