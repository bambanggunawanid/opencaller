package dev.opencaller.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Opt-in WhatsApp call warnings (warn-only — Android provides no API to
 * screen or block another app's self-managed VoIP calls; the same
 * isolation that stops malware from spying on your calls stops us).
 *
 * Privacy contract, enforced in code and verifiable in source:
 * 1. Only packages in [WATCHED] are processed — the first line returns for
 *    every other app's notifications.
 * 2. Only CATEGORY_CALL notifications are read, and only their title (the
 *    caller display string WhatsApp shows).
 * 3. Nothing is stored beyond the local screening history; nothing is
 *    transmitted; the lookup is the same offline DB/heuristics path as
 *    cellular screening.
 *
 * The binding itself is off until the user enables notification access in
 * system settings (surfaced honestly in the app's settings UI).
 */
class CallNotificationListener : NotificationListenerService() {

  private val recentlyWarned = HashMap<String, Long>()

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val appLabel = WATCHED[sbn.packageName] ?: return
    val notification = sbn.notification ?: return
    if (notification.category != Notification.CATEGORY_CALL) return

    // For non-contacts WhatsApp titles the caller's number; for saved
    // contacts it's a name (no digits) — then there is nothing to check.
    val title =
      notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
    val digits = title.filter { it.isDigit() }
    if (digits.length < 7) return

    // WhatsApp re-posts the call notification during the ring; warn once.
    val now = System.currentTimeMillis()
    synchronized(recentlyWarned) {
      recentlyWarned.entries.removeAll { now - it.value > 60_000 }
      if (recentlyWarned.containsKey(digits)) return
      recentlyWarned[digits] = now
    }

    val ruleVerdict =
      RuleEngine.evaluate(digits, Prefs.allowRules(this), Prefs.blockRules(this))
    if (ruleVerdict == RuleEngine.Verdict.ALLOW) return

    val detail = if (ruleVerdict == RuleEngine.Verdict.BLOCK) {
      "user-block"
    } else {
      val hit = DbManager.lookup(this, digits)
      hit?.let { "${it.category}:${it.reportCount}" }
        ?: NativeCore.nativeHeuristic(digits, Prefs.ownNumber(this))
          ?.let { "heuristic:$it" }
    } ?: return

    Notifier.postVerdict(this, title.trim(), Prefs.Action.ALLOW, detail, appLabel)
    DbManager.logEvent(this, digits, "warned:${appLabel.lowercase().replace(' ', '-')}:$detail")
  }

  private companion object {
    val WATCHED = buildMap {
      put("com.whatsapp", "WhatsApp")
      put("com.whatsapp.w4b", "WhatsApp Business")
      // Debug builds also watch our own package so the in-app "simulate"
      // button can exercise this whole pipeline without a second phone.
      // No loop risk: our warnings are CATEGORY_STATUS, filtered above.
      if (BuildConfig.DEBUG) put(BuildConfig.APPLICATION_ID, "Test")
    }
  }
}
