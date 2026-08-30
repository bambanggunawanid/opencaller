package dev.opencaller.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * F1 label surface. A CallScreeningService cannot draw on the ring screen
 * without being the default dialer (a takeover we deliberately avoid —
 * PRD §7), so the verdict is shown as a high-priority heads-up
 * notification while the call rings / after it is blocked. This is the
 * Play-policy-safe surface for a screening-role app.
 *
 * Not notified: unknown numbers (noise), user-allowed calls (the user
 * already decided), and silence-unknown mode hits (a mode, not a verdict).
 */
object Notifier {
  const val CHANNEL = "screening"

  fun ensureChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
      NotificationChannel(
        CHANNEL,
        "Call warnings",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Why a call was flagged, silenced, or blocked"
      },
    )
  }

  fun postVerdict(
    context: Context,
    number: String,
    action: Prefs.Action,
    detail: String,
    appLabel: String? = null,
  ) {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return
    ensureChannel(context)

    val callWord = if (appLabel == null) "call" else "$appLabel call"
    val title = when (action) {
      Prefs.Action.REJECT -> "Blocked $callWord"
      Prefs.Action.SILENCE -> "Silenced suspicious $callWord"
      Prefs.Action.ALLOW -> "⚠ Suspicious $callWord"
    }
    val notification = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText("$number — ${friendly(detail)}")
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)
      .build()
    try {
      // One slot per number: repeat calls update instead of stacking.
      nm.notify(number.hashCode(), notification)
    } catch (_: SecurityException) {
      // POST_NOTIFICATIONS revoked mid-flight; the call outcome stands.
    }
  }

  private fun friendly(detail: String): String {
    val parts = detail.split(':')
    return when (parts[0]) {
      "user-block" -> "matched your block rule"
      "heuristic" -> "suspicious (${parts.getOrElse(1) { "?" }})"
      else -> {
        val category = parts[0]
        val count = parts.getOrNull(1)
        if (count != null) "$category — $count report(s)" else category
      }
    }
  }
}
