package dev.opencaller.app

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * The hot path (PRD F1/F2): the OS hands us a ringing call; we answer from
 * the local database only — this method never touches the network (there is
 * no INTERNET permission to touch it with).
 *
 * M0 default policy (per-category settings arrive with F2 UI):
 *   scam / robocall  → silence (rings silently, user still sees the call)
 *   everything else  → allow; the hit is recorded in local history
 */
class ScreeningService : CallScreeningService() {

  override fun onScreenCall(callDetails: Call.Details) {
    val number = callDetails.handle?.schemeSpecificPart
    if (number.isNullOrBlank()) {
      respondToCall(callDetails, CallResponse.Builder().build())
      return
    }

    val hit = DbManager.lookup(this, number)
    val silence = hit != null && (hit.category == "scam" || hit.category == "robocall")

    val response = CallResponse.Builder()
      .setSilenceCall(silence)
      .build()
    respondToCall(callDetails, response)

    DbManager.logEvent(
      this,
      number,
      when {
        hit == null -> "unknown"
        silence -> "silenced:${hit.category}:${hit.reportCount}"
        else -> "flagged:${hit.category}:${hit.reportCount}"
      },
    )
  }
}
