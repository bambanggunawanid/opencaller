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
        L10n.str(context, R.string.notif_channel_name),
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = L10n.str(context, R.string.notif_channel_desc)
      },
    )
  }

  fun postVerdict(
    context: Context,
    number: String,
    action: Prefs.Action,
    detail: String,
    what: String? = null,
  ) {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return
    ensureChannel(context)

    val callWord = what ?: L10n.str(context, R.string.what_call)
    val title = when (action) {
      Prefs.Action.REJECT -> L10n.str(context, R.string.notif_blocked, callWord)
      Prefs.Action.SILENCE -> L10n.str(context, R.string.notif_silenced, callWord)
      Prefs.Action.ALLOW -> L10n.str(context, R.string.notif_suspicious, callWord)
    }
    val body = "$number — ${friendly(context, detail)}"
    val builder = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(body)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)

    // Screen-off/locked case of the large badge: overlays cannot draw over
    // the keyguard, so attach a full-screen intent (fires only when the
    // device is not in use; in-use devices get the heads-up + overlay).
    if (Prefs.overlayEnabled(context) && canUseFullScreen(context)) {
      val intent = android.content.Intent(context, WarningActivity::class.java)
        .putExtra(WarningActivity.EXTRA_TITLE, title)
        .putExtra(WarningActivity.EXTRA_BODY, body)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
      builder.setFullScreenIntent(
        android.app.PendingIntent.getActivity(
          context,
          number.hashCode(),
          intent,
          android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            android.app.PendingIntent.FLAG_IMMUTABLE,
        ),
        true,
      )
    }
    val notification = builder.build()
    try {
      // One slot per number: repeat calls update instead of stacking.
      nm.notify(number.hashCode(), notification)
    } catch (_: SecurityException) {
      // POST_NOTIFICATIONS revoked mid-flight; the call outcome stands.
    }
  }

  /** Android 14+ can revoke full-screen-intent use per app. */
  fun canUseFullScreen(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 34) return true
    return context.getSystemService(NotificationManager::class.java)
      ?.canUseFullScreenIntent() == true
  }

  fun friendly(context: Context, detail: String): String {
    val parts = detail.split(':')
    return when (parts[0]) {
      "user-block" -> L10n.str(context, R.string.friendly_user_block)
      "heuristic" ->
        L10n.str(context, R.string.friendly_heuristic, parts.getOrElse(1) { "?" })
      else -> {
        val category = L10n.category(context, parts[0])
        val count = parts.getOrNull(1)
        if (count != null) L10n.str(context, R.string.friendly_reports, category, count)
        else category
      }
    }
  }
}
