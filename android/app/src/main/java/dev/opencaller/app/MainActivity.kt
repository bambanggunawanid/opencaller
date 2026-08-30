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

    item { Text("Recent screening events", style = MaterialTheme.typography.titleMedium) }
    items(events) { e ->
      Text("${e.number} — ${e.verdict}", style = MaterialTheme.typography.bodyMedium)
    }
  }
}
