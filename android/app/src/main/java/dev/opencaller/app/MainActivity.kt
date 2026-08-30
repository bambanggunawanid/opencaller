package dev.opencaller.app

import android.app.role.RoleManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    DbManager.ensureOpen(this)
    UpdateScheduler.sync(this)
    setContent {
      MaterialTheme { Surface(Modifier.fillMaxSize()) { HomeScreen() } }
    }
  }
}

@Composable
fun HomeScreen() {
  val context = LocalContext.current
  val roleManager = context.getSystemService(RoleManager::class.java)
  var roleHeld by remember {
    mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true)
  }
  val roleLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
  }

  // F1 warnings need POST_NOTIFICATIONS on Android 13+.
  val notifLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) {}
  androidx.compose.runtime.LaunchedEffect(Unit) {
    Notifier.ensureChannel(context)
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
      android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  var query by remember { mutableStateOf("") }
  var result by remember { mutableStateOf<String?>(null) }
  var updateStatus by remember { mutableStateOf<String?>(null) }
  val events = remember { DbManager.recentEvents(context) }

  LazyColumn(
    Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { Text("OpenCaller", style = MaterialTheme.typography.headlineMedium) }

    item {
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            if (roleHeld) "✔ Protecting your calls"
            else "Not active — grant the call screening role",
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            if (DbManager.verified)
              "Database: ${DbManager.entryCount()} numbers, signature verified, " +
                "built ${java.time.LocalDate.ofEpochDay(DbManager.builtDays().toLong())}"
            else
              "Database: signature verification FAILED — lookups disabled",
            style = MaterialTheme.typography.bodyMedium,
          )
          if (!roleHeld) {
            Button(onClick = {
              roleManager
                ?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                ?.let { roleLauncher.launch(it) }
            }) { Text("Enable call screening") }
          }
        }
      }
    }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          label = { Text("Check a number") },
          modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
          val hit = DbManager.lookup(context, query)
          result = if (hit == null) "$query: not in database"
          else "$query: ${hit.category} — ${hit.reportCount} report(s)"
        }) { Text("Lookup") }
        result?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
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

    item { Text("Country databases", style = MaterialTheme.typography.titleMedium) }
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
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              if (info != null)
                "${cc.uppercase()} — ${info.entries} numbers, built " +
                  java.time.LocalDate.ofEpochDay(info.builtDays.toLong())
              else "${cc.uppercase()} — downloads with next update",
              style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = {
              Prefs.setCountryEnabled(context, cc, cc !in enabled)
              enabled = Prefs.enabledCountries(context)
              DbManager.reload(context)
            }) { Text(if (cc in enabled) "ON" else "OFF") }
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

    item { Text("Screening actions", style = MaterialTheme.typography.titleMedium) }
    items(Prefs.CATEGORIES + Prefs.HEURISTIC) { category ->
      var action by remember { mutableStateOf(Prefs.action(context, category)) }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          if (category == Prefs.HEURISTIC) "suspicious (spoofed/invalid)" else category,
          style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = {
          val next = Prefs.Action.entries[(action.ordinal + 1) % Prefs.Action.entries.size]
          action = next
          Prefs.setAction(context, category, next)
        }) { Text(action.name) }
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

    item {
      var mode by remember { mutableStateOf(Prefs.updateMode(context)) }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Weekly database updates", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = {
          val next =
            Prefs.UpdateMode.entries[(mode.ordinal + 1) % Prefs.UpdateMode.entries.size]
          mode = next
          Prefs.setUpdateMode(context, next)
          UpdateScheduler.sync(context)
        }) { Text(mode.name.replace('_', ' ')) }
      }
    }

    item {
      var refresh by remember { mutableStateOf(0) }
      val listenerOn = remember(refresh) {
        androidx.core.app.NotificationManagerCompat
          .getEnabledListenerPackages(context)
          .contains(context.packageName)
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("WhatsApp call warnings", style = MaterialTheme.typography.bodyLarge)
          TextButton(onClick = {
            context.startActivity(
              android.content.Intent(
                android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
              ),
            )
            refresh++
          }) { Text(if (listenerOn) "ON — manage" else "OFF — enable") }
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

    item { Text("Your rules", style = MaterialTheme.typography.titleMedium) }
    item {
      var ruleInput by remember { mutableStateOf("") }
      var blockRules by remember { mutableStateOf(Prefs.blockRules(context).sorted()) }
      var allowRules by remember { mutableStateOf(Prefs.allowRules(context).sorted()) }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
          value = ruleInput,
          onValueChange = { ruleInput = it },
          label = { Text("Number or prefix (end with * for prefix)") },
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = {
            if (Prefs.addRule(context, block = true, ruleInput)) {
              blockRules = Prefs.blockRules(context).sorted()
              ruleInput = ""
            }
          }) { Text("Block") }
          Button(onClick = {
            if (Prefs.addRule(context, block = false, ruleInput)) {
              allowRules = Prefs.allowRules(context).sorted()
              ruleInput = ""
            }
          }) { Text("Always allow") }
        }
        for (rule in blockRules) {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("⛔ $rule", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
              Prefs.removeRule(context, block = true, rule)
              blockRules = Prefs.blockRules(context).sorted()
            }) { Text("remove") }
          }
        }
        for (rule in allowRules) {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("✅ $rule", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
              Prefs.removeRule(context, block = false, rule)
              allowRules = Prefs.allowRules(context).sorted()
            }) { Text("remove") }
          }
        }
      }
    }

    item {
      var silenceUnknown by remember { mutableStateOf(Prefs.silenceUnknown(context)) }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Silence all unknown numbers", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = {
          silenceUnknown = !silenceUnknown
          Prefs.setSilenceUnknown(context, silenceUnknown)
        }) { Text(if (silenceUnknown) "ON" else "OFF") }
      }
    }

    item { Text("Recent screening events", style = MaterialTheme.typography.titleMedium) }
    items(events) { e ->
      Text("${e.number} — ${e.verdict}", style = MaterialTheme.typography.bodyMedium)
    }
  }
}
