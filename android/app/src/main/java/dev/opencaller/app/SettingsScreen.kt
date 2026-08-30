package dev.opencaller.app

import android.app.Activity
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
import androidx.compose.ui.res.stringResource
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
    item {
      Text(
        stringResource(R.string.settings_title),
        style = MaterialTheme.typography.headlineMedium,
      )
    }

    // ---- Language ----
    item { SectionHeader(stringResource(R.string.section_language)) }
    item { LanguagePicker() }

    // ---- Protection ----
    item { SectionHeader(stringResource(R.string.section_actions)) }
    items(count = ACTION_CATEGORIES.size) { i ->
      val (category, labelRes) = ACTION_CATEGORIES[i]
      ActionSelector(category, stringResource(labelRes))
    }

    item { SectionHeader(stringResource(R.string.section_unknown)) }
    item {
      var silenceUnknown by remember { mutableStateOf(Prefs.silenceUnknown(context)) }
      SwitchRow(
        stringResource(R.string.silence_unknown_title),
        stringResource(R.string.silence_unknown_sub),
        silenceUnknown,
      ) {
        silenceUnknown = it
        Prefs.setSilenceUnknown(context, it)
      }
    }
    item {
      var wangiri by remember { mutableStateOf(Prefs.wangiriEnabled(context)) }
      SwitchRow(
        stringResource(R.string.wangiri_title),
        stringResource(R.string.wangiri_sub),
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
          label = { Text(stringResource(R.string.own_number_label)) },
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          stringResource(R.string.own_number_sub),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    // ---- Warnings ----
    item { SectionHeader(stringResource(R.string.section_warnings)) }
    item { OverlaySetting(permTick) }
    item { WhatsAppSetting(permTick) }
    item { SmsSetting() }

    // ---- Database ----
    item { SectionHeader(stringResource(R.string.section_db)) }
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
              stringResource(
                R.string.db_row_sub,
                info.entries,
                LocalDate.ofEpochDay(info.builtDays.toLong()).toString(),
              )
            else stringResource(R.string.db_row_pending),
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
            stringResource(R.string.db_sim_note, sim.uppercase()),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
    item {
      var mode by remember { mutableStateOf(Prefs.updateMode(context)) }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          stringResource(R.string.update_mode_title),
          style = MaterialTheme.typography.bodyLarge,
        )
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
                stringResource(
                  when (m) {
                    Prefs.UpdateMode.OFF -> R.string.update_off
                    Prefs.UpdateMode.WIFI_ONLY -> R.string.update_wifi
                    Prefs.UpdateMode.ANY_NETWORK -> R.string.update_any
                  },
                ),
              )
            }
          }
        }
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val checking = stringResource(R.string.update_checking)
        Button(onClick = {
          updateStatus = checking
          Thread {
            val msg = UpdateManager.checkAndApply(context)
            (context as? ComponentActivity)?.runOnUiThread { updateStatus = msg }
          }.start()
        }) { Text(stringResource(R.string.update_check)) }
        updateStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
      }
    }

    if (BuildConfig.DEBUG) {
      item { SectionHeader("Debug tools") }
      item { DebugTools() }
    }

    item {
      Text(
        stringResource(R.string.about_line, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 16.dp),
      )
    }
  }
}

private val ACTION_CATEGORIES: List<Pair<String, Int>> = listOf(
  "scam" to R.string.cat_scam,
  "robocall" to R.string.cat_robocall,
  "telemarketing" to R.string.cat_telemarketing,
  "debt-collection" to R.string.cat_debt,
  "survey" to R.string.cat_survey,
  "other" to R.string.cat_other,
  "sms-spam" to R.string.cat_sms_spam,
  Prefs.HEURISTIC to R.string.cat_suspicious,
)

@Composable
private fun LanguagePicker() {
  val context = LocalContext.current
  var lang by remember { mutableStateOf(Prefs.language(context)) }
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    L10n.CHOICES.forEachIndexed { i, tag ->
      SegmentedButton(
        selected = lang == tag,
        onClick = {
          if (lang != tag) {
            lang = tag
            Prefs.setLanguage(context, tag)
            // Strings resolve at activity creation — rebuild in place.
            (context as? Activity)?.recreate()
          }
        },
        shape = SegmentedButtonDefaults.itemShape(index = i, count = L10n.CHOICES.size),
      ) {
        Text(
          when (tag) {
            "" -> stringResource(R.string.lang_system)
            "en" -> "English"
            else -> "Indonesia"
          },
        )
      }
    }
  }
}

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
            stringResource(
              when (a) {
                Prefs.Action.ALLOW -> R.string.action_allow
                Prefs.Action.SILENCE -> R.string.action_silence
                Prefs.Action.REJECT -> R.string.action_reject
              },
            ),
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
      stringResource(R.string.badge_title),
      stringResource(R.string.badge_sub),
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
        stringResource(R.string.badge_not_granted),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
      TextButton(onClick = grantIntent) { Text(stringResource(R.string.badge_grant)) }
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
      }) { Text(stringResource(R.string.badge_fullscreen)) }
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
        stringResource(R.string.wa_title),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
      }) { Text(stringResource(if (listenerOn) R.string.wa_on else R.string.wa_off)) }
    }
    Text(
      stringResource(R.string.wa_sub),
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun SmsSetting() {
  val context = LocalContext.current
  var smsMode by remember { mutableStateOf(Prefs.smsMode(context)) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(stringResource(R.string.sms_title), style = MaterialTheme.typography.bodyLarge)
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
            stringResource(
              when (m) {
                Prefs.SmsMode.OFF -> R.string.sms_off
                Prefs.SmsMode.WARN -> R.string.sms_warn
                Prefs.SmsMode.MUTE -> R.string.sms_mute
              },
            ),
          )
        }
      }
    }
    Text(
      stringResource(R.string.sms_sub),
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun DebugTools() {
  val context = LocalContext.current
  // Debug-only surface — deliberately not translated.
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
