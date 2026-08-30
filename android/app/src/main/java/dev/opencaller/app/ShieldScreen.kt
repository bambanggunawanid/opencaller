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
import androidx.compose.ui.res.stringResource
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
              stringResource(
                when {
                  active -> R.string.shield_active
                  !roleHeld -> R.string.shield_inactive
                  else -> R.string.shield_db_problem
                },
              ),
              style = MaterialTheme.typography.headlineSmall,
            )
          }
          Text(
            if (DbManager.verified)
              stringResource(
                R.string.shield_db_ok,
                DbManager.entryCount(),
                LocalDate.ofEpochDay(DbManager.builtDays().toLong()).toString(),
              )
            else stringResource(R.string.shield_db_fail),
            style = MaterialTheme.typography.bodyMedium,
          )
          if (!roleHeld) {
            Text(
              stringResource(R.string.shield_role_explain),
              style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
              roleManager
                ?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                ?.let { roleLauncher.launch(it) }
            }) { Text(stringResource(R.string.shield_enable)) }
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
            Text(stringResource(R.string.pause_title), style = MaterialTheme.typography.titleMedium)
          }
          Text(
            stringResource(R.string.pause_body),
            style = MaterialTheme.typography.bodySmall,
          )
          OutlinedButton(onClick = {
            pausedUntil = if (pausedUntil > now) 0L else now + 45 * 60 * 1000
            Prefs.setPausedUntil(context, pausedUntil)
          }) {
            Text(
              if (pausedUntil > now)
                stringResource(
                  R.string.pause_until,
                  SimpleDateFormat("HH:mm").format(pausedUntil),
                )
              else stringResource(R.string.pause_start),
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
          Text(stringResource(R.string.lookup_title), style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.lookup_label)) },
            modifier = Modifier.fillMaxWidth(),
          )
          Button(onClick = {
            val hit = DbManager.lookup(context, query)
            result = if (hit == null) L10n.str(context, R.string.lookup_miss, query)
            else L10n.str(
              context,
              R.string.lookup_hit,
              query,
              L10n.category(context, hit.category),
              hit.reportCount,
            )
          }) { Text(stringResource(R.string.lookup_button)) }
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
          Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.titleMedium)
          TextButton(onClick = onOpenActivity) { Text(stringResource(R.string.recent_see_all)) }
        }
      }
      items(events.take(3).size) { i -> EventRow(events[i]) }
    }
  }
}
