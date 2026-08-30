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

    val title =
      notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
    val digits = extractNumberDigits(notification, title)
    if (digits == null) {
      // Saved contact with no number in the extras — nothing to check.
      // Debug builds leave a local breadcrumb so field testing can see
      // exactly what this WhatsApp version attaches.
      if (BuildConfig.DEBUG) {
        DbManager.logEvent(
          this,
          "-",
          "wa-debug: title='$title' people=${peopleUris(notification)}",
        )
      }
      return
    }

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

  /**
   * The caller's number, from (in order): the notification title (WhatsApp
   * shows the number there for NON-contacts), the call-style `Person`
   * attachments (URI is typically `tel:+62…` — present even for saved
   * contacts, and readable without any contacts permission), and the
   * legacy people extra. Null when no ≥7-digit number is found anywhere.
   */
  private fun extractNumberDigits(notification: Notification, title: String): String? {
    title.filter { it.isDigit() }.takeIf { it.length >= 7 }?.let { return it }
    for (uri in peopleUris(notification)) {
      if (!uri.startsWith("tel:", ignoreCase = true)) continue
      uri.filter { it.isDigit() }.takeIf { it.length >= 7 }?.let { return it }
    }
    return null
  }

  private fun peopleUris(notification: Notification): List<String> {
    val extras = notification.extras ?: return emptyList()
    val uris = mutableListOf<String>()
    @Suppress("DEPRECATION")
    extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
      ?.forEach { person -> person.uri?.let { uris.add(it) } }
    @Suppress("DEPRECATION")
    extras.getStringArray(Notification.EXTRA_PEOPLE)?.let { uris.addAll(it) }
    return uris
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
