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

    val hit = DbManager.lookup(this, number)
    val (category, detail) = if (hit != null) {
      hit.category to "${hit.category}:${hit.reportCount}"
    } else {
      val suspicion = NativeCore.nativeHeuristic(
        normalizeCandidate(number),
        Prefs.ownNumber(this),
      )
      if (suspicion != null) Prefs.HEURISTIC to "heuristic:$suspicion"
      else null to null
    }

    val action = category?.let { Prefs.action(this, it) } ?: Prefs.Action.ALLOW
    val response = when (action) {
      Prefs.Action.ALLOW -> CallResponse.Builder().build()
      Prefs.Action.SILENCE -> CallResponse.Builder().setSilenceCall(true).build()
      Prefs.Action.REJECT -> CallResponse.Builder()
        .setDisallowCall(true)
        .setRejectCall(true)
        .build()
    }
    respondToCall(callDetails, response)

    DbManager.logEvent(
      this,
      number,
      when {
        detail == null -> "unknown"
        action == Prefs.Action.ALLOW -> "flagged:$detail"
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
