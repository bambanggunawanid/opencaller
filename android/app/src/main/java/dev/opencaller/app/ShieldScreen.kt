package dev.opencaller.app

import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.LocalDate

/**
 * Tab 1 — the at-a-glance answer to "am I protected right now?", plus the
 * two things people reach for in the moment: pausing for an expected
 * courier call and checking a number by hand.
 */
@Composable
fun ShieldScreen(onOpenActivity: () -> Unit) {
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

  val active = roleHeld && DbManager.verified
  val events = remember { DbManager.recentEvents(context) }

  LazyColumn(
    Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.errorContainer,
        ),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              if (active) Icons.Filled.Shield else Icons.Filled.GppBad,
              contentDescription = null,
              modifier = Modifier.size(40.dp),
            )
            Text(
              when {
                active -> "Protecting your calls"
                !roleHeld -> "Not active"
                else -> "Database problem"
              },
              style = MaterialTheme.typography.headlineSmall,
            )
          }
          Text(
            if (DbManager.verified)
              "${DbManager.entryCount()} known spam numbers on this phone, " +
                "signature verified, built " +
                LocalDate.ofEpochDay(DbManager.builtDays().toLong())
            else
              "Database signature verification FAILED — lookups disabled",
            style = MaterialTheme.typography.bodyMedium,
          )
          if (!roleHeld) {
            Text(
              "Grant the call screening role so Android sends ringing calls " +
                "to OpenCaller. Everything stays on this device.",
              style = MaterialTheme.typography.bodyMedium,
            )
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
      var pausedUntil by remember { mutableStateOf(Prefs.pausedUntil(context)) }
      val now = System.currentTimeMillis()
      OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.HourglassTop, contentDescription = null)
            Text("Expecting a call?", style = MaterialTheme.typography.titleMedium)
          }
          Text(
            "Pause lets unknown numbers ring normally for 45 minutes " +
              "(couriers, drivers). Known spammers and your rules stay blocked.",
            style = MaterialTheme.typography.bodySmall,
          )
          OutlinedButton(onClick = {
            pausedUntil = if (pausedUntil > now) 0L else now + 45 * 60 * 1000
            Prefs.setPausedUntil(context, pausedUntil)
          }) {
            Text(
              if (pausedUntil > now)
                "Paused until ${SimpleDateFormat("HH:mm").format(pausedUntil)} — tap to resume"
              else "Pause for 45 minutes",
            )
          }
        }
      }
    }

    item {
      var query by rememberSaveable { mutableStateOf("") }
      var result by remember { mutableStateOf<String?>(null) }
      OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Check a number", style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Phone number") },
            modifier = Modifier.fillMaxWidth(),
          )
          Button(onClick = {
            val hit = DbManager.lookup(context, query)
            result = if (hit == null) "$query — not in the database"
            else "$query — ${hit.category}, ${hit.reportCount} report(s)"
          }) { Text("Look up") }
          result?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
      }
    }

    if (events.isNotEmpty()) {
      item {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Recent", style = MaterialTheme.typography.titleMedium)
          TextButton(onClick = onOpenActivity) { Text("See all") }
        }
      }
      items(events.take(3).size) { i -> EventRow(events[i]) }
    }
  }
}
