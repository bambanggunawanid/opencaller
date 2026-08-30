package dev.opencaller.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalDate

/** Tab 4 — everything configurable, grouped so it reads top-to-bottom. */
@Composable
fun SettingsScreen() {
  val context = LocalContext.current

  // Permission-backed rows (overlay, notification access, full-screen)
  // re-check when the user comes back from a system settings screen.
  var permTick by remember { mutableIntStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) permTick++
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  var updateStatus by remember { mutableStateOf<String?>(null) }

  LazyColumn(
    Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

    // ---- Protection ----
    item { SectionHeader("What to do with flagged calls") }
    items(count = ACTION_CATEGORIES.size) { i ->
      val (category, label) = ACTION_CATEGORIES[i]
      ActionSelector(category, label)
    }

    item { SectionHeader("Unknown callers") }
    item {
      var silenceUnknown by remember { mutableStateOf(Prefs.silenceUnknown(context)) }
      SwitchRow(
        "Silence all unknown numbers",
        "Calls from numbers not in your contacts ring silently. " +
          "Use the Shield tab's pause when you expect a courier.",
        silenceUnknown,
      ) {
        silenceUnknown = it
        Prefs.setSilenceUnknown(context, it)
      }
    }
    item {
      var wangiri by remember { mutableStateOf(Prefs.wangiriEnabled(context)) }
      SwitchRow(
        "Learn from missed calls",
        "Rotating spammers can't hide their SIM batch: when unknown " +
          "numbers ring briefly and hang up, their whole number block is " +
          "silenced locally for 48h (two strikes needed — one missed " +
          "courier call is never enough). Learned on this phone only; " +
          "needs notification access.",
        wangiri,
      ) {
        wangiri = it
        Prefs.setWangiriEnabled(context, it)
      }
    }
    item {
      var ownNumber by remember { mutableStateOf(Prefs.ownNumber(context)) }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
          value = ownNumber,
          onValueChange = {
            ownNumber = it
            Prefs.setOwnNumber(context, it)
          },
          label = { Text("Your number (optional, for spoof detection)") },
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          "Used only on this device to spot calls faking your own prefix.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    // ---- Warnings ----
    item { SectionHeader("Warnings") }
    item { OverlaySetting(permTick) }
    item { WhatsAppSetting(permTick) }
    item { SmsSetting() }

    // ---- Database ----
    item { SectionHeader("Spam database") }
    item {
      var enabled by remember { mutableStateOf(Prefs.enabledCountries(context)) }
      // Re-read after toggles AND after updates (field bug: row showed the
      // stale pre-update shard while the status card showed the new one).
      val infos = remember(enabled, updateStatus) {
        DbManager.shardInfos().associateBy { it.country }
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (cc in Prefs.AVAILABLE_SHARDS.sorted()) {
          val info = infos[cc]
          SwitchRow(
            title = cc.uppercase(),
            subtitle = if (info != null)
              "${info.entries} numbers, built " +
                LocalDate.ofEpochDay(info.builtDays.toLong())
            else "downloads with the next update",
            checked = cc in enabled,
          ) {
            Prefs.setCountryEnabled(context, cc, it)
            enabled = Prefs.enabledCountries(context)
            DbManager.reload(context)
          }
        }
        val sim = Prefs.simCountry(context)
        if (sim.isNotEmpty() && sim !in Prefs.AVAILABLE_SHARDS) {
          Text(
            "No public spam data exists yet for your SIM country " +
              "(${sim.uppercase()}) — calls there are still protected by " +
              "spoofing/invalid-number heuristics.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
    item {
      var mode by remember { mutableStateOf(Prefs.updateMode(context)) }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Weekly automatic updates", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
          Prefs.UpdateMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
              selected = mode == m,
              onClick = {
                mode = m
                Prefs.setUpdateMode(context, m)
                UpdateScheduler.sync(context)
              },
              shape = SegmentedButtonDefaults.itemShape(
                index = i,
                count = Prefs.UpdateMode.entries.size,
              ),
            ) {
              Text(
                when (m) {
                  Prefs.UpdateMode.OFF -> "Off"
                  Prefs.UpdateMode.WIFI_ONLY -> "Wi-Fi only"
                  Prefs.UpdateMode.ANY_NETWORK -> "Any network"
                },
              )
            }
          }
        }
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = {
          updateStatus = "Checking…"
          Thread {
            val msg = UpdateManager.checkAndApply(context)
            (context as? ComponentActivity)?.runOnUiThread { updateStatus = msg }
          }.start()
        }) { Text("Check for database update") }
        updateStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
      }
    }

    if (BuildConfig.DEBUG) {
      item { SectionHeader("Debug tools") }
      item { DebugTools() }
    }

    item {
      Text(
        "OpenCaller ${BuildConfig.VERSION_NAME} — free, open source, and " +
          "fully offline. No account, no ads, no data leaves your phone.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 16.dp),
      )
    }
  }
}

private val ACTION_CATEGORIES: List<Pair<String, String>> = listOf(
  "scam" to "Scam",
  "robocall" to "Robocall",
  "telemarketing" to "Telemarketing",
  "debt-collection" to "Debt collection",
  "survey" to "Survey",
  "other" to "Other reports",
  "sms-spam" to "SMS spam",
  Prefs.HEURISTIC to "Suspicious (spoofed/invalid)",
)

@Composable
private fun SectionHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.padding(top = 12.dp),
  )
}

@Composable
private fun SwitchRow(
  title: String,
  subtitle: String?,
  checked: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
      )
      Switch(checked = checked, onCheckedChange = onToggle)
    }
    if (subtitle != null) {
      Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun ActionSelector(category: String, label: String) {
  val context = LocalContext.current
  var action by remember { mutableStateOf(Prefs.action(context, category)) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      Prefs.Action.entries.forEachIndexed { i, a ->
        SegmentedButton(
          selected = action == a,
          onClick = {
            action = a
            Prefs.setAction(context, category, a)
          },
          shape = SegmentedButtonDefaults.itemShape(
            index = i,
            count = Prefs.Action.entries.size,
          ),
        ) {
          Text(
            when (a) {
              Prefs.Action.ALLOW -> "Allow"
              Prefs.Action.SILENCE -> "Silence"
              Prefs.Action.REJECT -> "Block"
            },
          )
        }
      }
    }
  }
}

@Composable
private fun OverlaySetting(permTick: Int) {
  val context = LocalContext.current
  val overlayGranted = remember(permTick) { Settings.canDrawOverlays(context) }
  var overlayPref by remember { mutableStateOf(Prefs.overlayEnabled(context)) }
  val grantIntent = {
    context.startActivity(
      Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
      ),
    )
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SwitchRow(
      "Large on-screen spam badge",
      "Shows a big red warning card over the screen while a flagged call " +
        "rings (also for WhatsApp/SMS warnings). Needs the 'Display over " +
        "other apps' / 'Appear on top' permission — used only to draw this " +
        "card; tap it to dismiss. When the screen is off or locked, the " +
        "warning lights the screen as a full-screen alert instead.",
      checked = overlayPref && overlayGranted,
    ) { wantOn ->
      if (wantOn) {
        overlayPref = true
        Prefs.setOverlayEnabled(context, true)
        if (!overlayGranted) grantIntent()
      } else {
        overlayPref = false
        Prefs.setOverlayEnabled(context, false)
      }
    }
    if (overlayPref && !overlayGranted) {
      Text(
        "Permission not granted yet — the badge cannot appear.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
      TextButton(onClick = grantIntent) { Text("Grant 'Appear on top'") }
    }
    if (Build.VERSION.SDK_INT >= 34 &&
      overlayPref && !Notifier.canUseFullScreen(context)
    ) {
      TextButton(onClick = {
        context.startActivity(
          Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
          ),
        )
      }) { Text("Allow full-screen alerts (screen-off warnings)") }
    }
  }
}

@Composable
private fun WhatsAppSetting(permTick: Int) {
  val context = LocalContext.current
  val listenerOn = remember(permTick) {
    NotificationManagerCompat
      .getEnabledListenerPackages(context)
      .contains(context.packageName)
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "WhatsApp call warnings",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
      }) { Text(if (listenerOn) "On — manage" else "Off — enable") }
    }
    Text(
      "Optional: warns when a reported number calls you on WhatsApp. " +
        "Uses Android's notification access — a broad permission; " +
        "OpenCaller only reads WhatsApp incoming-call notifications " +
        "(open source, verifiable) and still works fully offline. " +
        "Warn-only: Android does not allow blocking VoIP calls. " +
        "Tip: WhatsApp Settings → Privacy → Calls can silence unknown callers.",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun SmsSetting() {
  val context = LocalContext.current
  var smsMode by remember { mutableStateOf(Prefs.smsMode(context)) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("SMS spam protection", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      Prefs.SmsMode.entries.forEachIndexed { i, m ->
        SegmentedButton(
          selected = smsMode == m,
          onClick = {
            smsMode = m
            Prefs.setSmsMode(context, m)
          },
          shape = SegmentedButtonDefaults.itemShape(
            index = i,
            count = Prefs.SmsMode.entries.size,
          ),
        ) {
          Text(
            when (m) {
              Prefs.SmsMode.OFF -> "Off"
              Prefs.SmsMode.WARN -> "Warn"
              Prefs.SmsMode.MUTE -> "Mute"
            },
          )
        }
      }
    }
    Text(
      "Optional, uses the same notification access. WARN adds a banner " +
        "next to spam texts. MUTE silences the notification for senders " +
        "in your block rules or reported for SMS spam — the message " +
        "still arrives in your inbox, just without the buzz. Only " +
        "sender info from your SMS app's notifications is read, " +
        "locally. True SMS blocking would require replacing your " +
        "messaging app — the trade we refuse. " +
        "Tip: block gateway senders by name, e.g. add a rule " +
        "\"IM3 Promo\" or \"3Untukmu\".",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun DebugTools() {
  val context = LocalContext.current
  // WhatsApp-style CATEGORY_CALL notifications from our own package (debug
  // builds watch it), exercising the full listener pipeline with numbers
  // known to the DB.
  val simulateCall = { id: Int, title: String, personTel: String? ->
    Notifier.ensureChannel(context)
    val nm = NotificationManagerCompat.from(context)
    val builder = NotificationCompat
      .Builder(context, Notifier.CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText("Incoming voice call (simulated)")
      .setCategory(NotificationCompat.CATEGORY_CALL)
    if (personTel != null) {
      builder.addPerson(
        Person.Builder().setName(title).setUri("tel:$personTel").build(),
      )
    }
    try {
      nm.notify(id, builder.build())
    } catch (_: SecurityException) {
    }
    Handler(Looper.getMainLooper()).postDelayed({ nm.cancel(id) }, 3000)
  }
  Column {
    TextButton(onClick = {
      when {
        !Settings.canDrawOverlays(context) ->
          Toast.makeText(
            context,
            "Blocked: overlay permission NOT granted",
            Toast.LENGTH_LONG,
          ).show()
        !Prefs.overlayEnabled(context) ->
          Toast.makeText(context, "Blocked: badge toggle is OFF", Toast.LENGTH_LONG)
            .show()
        else -> OverlayWarning.show(
          context,
          "⚠ Spam call silenced",
          "+1 828-300-3919 — debt-collection — 1 report(s)",
        )
      }
    }) { Text("Test badge") }
    TextButton(onClick = {
      simulateCall(424242, "+1 828-300-3919", null)
    }) { Text("Simulate unknown-number WhatsApp call") }
    TextButton(onClick = {
      // Saved-contact case: name in the title, number only in the Person
      // tel: URI — tests the extras extraction path.
      simulateCall(424243, "Test Contact 🐥", "+19518514805")
    }) { Text("Simulate saved-contact WhatsApp call") }
    TextButton(onClick = {
      // Sender is sms-spam-category in the v2 shard (208k+); with the old
      // bundled DB it's unknown — add a Block rule to test rule-based mute.
      Notifier.ensureChannel(context)
      val nm = NotificationManagerCompat.from(context)
      val fake = NotificationCompat
        .Builder(context, Notifier.CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("+1 410-553-5239")
        .setContentText("KAMU HOKI! You won a prize (simulated)")
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .build()
      try {
        nm.notify(424244, fake)
      } catch (_: SecurityException) {
      }
      Handler(Looper.getMainLooper()).postDelayed({ nm.cancel(424244) }, 5000)
    }) { Text("Simulate spam SMS") }
  }
}
