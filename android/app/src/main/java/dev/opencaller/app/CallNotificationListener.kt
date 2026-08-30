package dev.opencaller.app

import android.app.Notification
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Opt-in notification-layer protection for surfaces Android won't let us
 * screen directly:
 * - WhatsApp calls: warn-only (no API can block another app's VoIP calls).
 * - SMS: warn or MUTE — muting cancels the SMS app's notification so spam
 *   lands silently in the inbox; the message itself is never touched.
 *   (True SMS blocking requires becoming the default SMS app — the
 *   Truecaller trade: full filtering power in exchange for custody of
 *   every message. We refuse that trade.)
 *
 * Privacy contract, enforced in code and verifiable in source:
 * 1. Only WhatsApp packages, the user's default SMS app, and (debug
 *    builds) our own test package are processed — everything else is
 *    dropped on the first line.
 * 2. Only call/message notifications are read, and only the sender
 *    display string and structured Person tel: URIs — never message
 *    bodies.
 * 3. Nothing is stored beyond the local screening history; nothing is
 *    transmitted; lookups are the same offline DB/rules path as cellular.
 *
 * Everything here is inert until the user enables notification access in
 * system settings, and SMS handling additionally requires the in-app SMS
 * mode to be WARN or MUTE (default OFF).
 */
class CallNotificationListener : NotificationListenerService() {

  private val recentlyWarned = HashMap<String, Long>()

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val pkg = sbn.packageName
    val notification = sbn.notification ?: return
    val isTest = BuildConfig.DEBUG && pkg == BuildConfig.APPLICATION_ID
    val callLabel = WATCHED_CALL_APPS[pkg]
    val isSmsApp = pkg == Telephony.Sms.getDefaultSmsPackage(this)

    when {
      (callLabel != null || isTest) &&
        notification.category == Notification.CATEGORY_CALL ->
        handleCall(notification, callLabel ?: "Test")

      (isSmsApp || isTest) && isMessageNotification(notification) ->
        handleSms(sbn, notification)
    }
  }

  private fun handleCall(notification: Notification, appLabel: String) {
    val title = titleOf(notification) ?: return
    val digits = extractNumberDigits(notification, title)
    if (digits == null) {
      // Saved contact with no number in the extras. Debug builds leave a
      // local breadcrumb showing what this WhatsApp version attaches.
      if (BuildConfig.DEBUG) {
        DbManager.logEvent(
          this,
          "-",
          "wa-debug: title='$title' people=${peopleUris(notification)}",
        )
      }
      return
    }
    if (!shouldProcess(digits)) return

    val ruleVerdict = RuleEngine.evaluate(
      number = digits,
      senderName = title,
      allowRules = Prefs.allowRules(this),
      blockRules = Prefs.blockRules(this),
    )
    if (ruleVerdict == RuleEngine.Verdict.ALLOW) return

    val detail = if (ruleVerdict == RuleEngine.Verdict.BLOCK) {
      "user-block"
    } else {
      val hit = DbManager.lookup(this, digits)
      hit?.let { "${it.category}:${it.reportCount}" }
        ?: NativeCore.nativeHeuristic(digits, Prefs.ownNumber(this))
          ?.let { "heuristic:$it" }
    } ?: return

    Notifier.postVerdict(this, title.trim(), Prefs.Action.ALLOW, detail, "$appLabel call")
    OverlayWarning.show(
      this,
      "⚠ Suspicious $appLabel call",
      "${title.trim()} — ${Notifier.friendly(detail)}",
    )
    DbManager.logEvent(this, digits, "warned:${appLabel.lowercase().replace(' ', '-')}:$detail")
  }

  private fun handleSms(sbn: StatusBarNotification, notification: Notification) {
    val mode = Prefs.smsMode(this)
    if (mode == Prefs.SmsMode.OFF) return
    val title = titleOf(notification) ?: return
    // Alphanumeric gateway IDs ("IM3 Promo") have no number — sender-name
    // rules still apply, so digits are optional here.
    val digits = extractNumberDigits(notification, title)

    val ruleVerdict = RuleEngine.evaluate(
      number = digits ?: "",
      senderName = title,
      allowRules = Prefs.allowRules(this),
      blockRules = Prefs.blockRules(this),
    )
    if (ruleVerdict == RuleEngine.Verdict.ALLOW) return

    var muteEligible = ruleVerdict == RuleEngine.Verdict.BLOCK
    val detail = if (ruleVerdict == RuleEngine.Verdict.BLOCK) {
      "user-block"
    } else {
      digits?.let { d ->
        DbManager.lookup(this, d)?.let { hit ->
          if (hit.category == "sms-spam") muteEligible = true
          "${hit.category}:${hit.reportCount}"
        }
      }
    } ?: return

    val key = digits ?: title.lowercase()
    if (mode == Prefs.SmsMode.MUTE && muteEligible) {
      // Silence the ping; the message stays untouched in the inbox.
      // Reposts are re-cancelled (no dedupe on purpose).
      try {
        cancelNotification(sbn.key)
      } catch (_: Exception) {
      }
      DbManager.logEvent(this, key, "muted:sms:$detail")
    } else {
      if (!shouldProcess(key)) return
      Notifier.postVerdict(this, title.trim(), Prefs.Action.ALLOW, detail, "text message")
      OverlayWarning.show(
        this,
        "⚠ Suspicious text message",
        "${title.trim()} — ${Notifier.friendly(detail)}",
      )
      DbManager.logEvent(this, key, "warned:sms:$detail")
    }
  }

  /** Warn at most once per sender per minute (apps repost while ringing). */
  private fun shouldProcess(key: String): Boolean {
    val now = System.currentTimeMillis()
    synchronized(recentlyWarned) {
      recentlyWarned.entries.removeAll { now - it.value > 60_000 }
      if (recentlyWarned.containsKey(key)) return false
      recentlyWarned[key] = now
    }
    return true
  }

  private fun titleOf(notification: Notification): String? =
    notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

  private fun isMessageNotification(notification: Notification): Boolean {
    if (notification.category == Notification.CATEGORY_MESSAGE) return true
    val template = notification.extras?.getString(Notification.EXTRA_TEMPLATE) ?: ""
    return template.endsWith("MessagingStyle")
  }

  /**
   * The sender's number, from (in order): the notification title (shown
   * for NON-contacts), the call-style `Person` attachments (URI is
   * typically `tel:+62…` — present even for saved contacts, readable
   * without any contacts permission), and the legacy people extra.
   * Null when no ≥7-digit number is found anywhere.
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
    val WATCHED_CALL_APPS = mapOf(
      "com.whatsapp" to "WhatsApp",
      "com.whatsapp.w4b" to "WhatsApp Business",
    )
  }
}
