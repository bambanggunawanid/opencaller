package dev.opencaller.app

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.CloudDownload
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
  val requestRole = {
    roleManager
      ?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
      ?.let { roleLauncher.launch(it) } ?: Unit
  }

  // The app must arrive armed: fire the one-tap system dialog immediately
  // on launch instead of hiding it behind a button (once per launch — a
  // decline still leaves the setup card and hero button).
  var autoAsked by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    if (!roleHeld && !autoAsked) {
      autoAsked = true
      requestRole()
    }
  }

  // Grant states re-check when the user returns from system settings.
  var setupTick by remember { mutableIntStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        setupTick++
        roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val overlayGranted = remember(setupTick) { Settings.canDrawOverlays(context) }
  val listenerOn = remember(setupTick) {
    NotificationManagerCompat.getEnabledListenerPackages(context)
      .contains(context.packageName)
  }
  var optionalHidden by remember { mutableStateOf(Prefs.setupOptionalHidden(context)) }

  // Sync state drives both the friendly update banner and the hero card's
  // DB line (which must refresh after a successful sync).
  var syncing by remember { mutableStateOf(false) }
  var syncStatus by remember { mutableStateOf<String?>(null) }
  var syncTick by remember { mutableIntStateOf(0) }
  val startSync: () -> Unit = {
    if (!syncing) {
      syncing = true
      Prefs.setLastSyncAttemptMillis(context, System.currentTimeMillis())
      Thread {
        val msg = UpdateManager.checkAndApply(context)
        (context as? android.app.Activity)?.runOnUiThread {
          syncing = false
          syncStatus = msg
          syncTick++
        }
      }.start()
    }
  }
  // Auto-sync on open when the list is stale — covers the fresh install
  // (the weekly job's first run is days away) and phones that missed it.
  LaunchedEffect(Unit) { if (UpdateManager.shouldAutoSync(context)) startSync() }

  val stale = remember(syncTick) { UpdateManager.isStale(context) }
  val neverSynced = remember(syncTick) { UpdateManager.neverSynced(context) }
  val dbVerified = remember(syncTick) { DbManager.verified }
  val dbCount = remember(syncTick) { DbManager.entryCount() }
  val dbBuilt = remember(syncTick) { DbManager.builtDays() }

  val active = roleHeld && dbVerified
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
            if (dbVerified)
              stringResource(
                R.string.shield_db_ok,
                dbCount,
                LocalDate.ofEpochDay(dbBuilt.toLong()).toString(),
              )
            else stringResource(R.string.shield_db_fail),
            style = MaterialTheme.typography.bodyMedium,
          )
          if (!roleHeld) {
            Text(
              stringResource(R.string.shield_role_explain),
              style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = requestRole) { Text(stringResource(R.string.shield_enable)) }
          }
        }
      }
    }

    // Finish-setup card: only the grants Android forbids an app from
    // switching on itself, each one tap, gone once complete.
    val overlayMissing = Prefs.overlayEnabled(context) && !overlayGranted
    val optionalMissing = !listenerOn && !optionalHidden
    if (!roleHeld || overlayMissing || optionalMissing) {
      item {
        OutlinedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              stringResource(R.string.setup_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              stringResource(R.string.setup_body),
              style = MaterialTheme.typography.bodySmall,
            )
            if (!roleHeld) {
              TextButton(onClick = requestRole) {
                Text(stringResource(R.string.setup_role))
              }
            }
            if (overlayMissing) {
              TextButton(onClick = {
                context.startActivity(
                  Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                  ),
                )
              }) { Text(stringResource(R.string.setup_overlay)) }
            }
            if (optionalMissing) {
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                TextButton(onClick = {
                  context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                  )
                }) { Text(stringResource(R.string.setup_listener)) }
                TextButton(onClick = {
                  optionalHidden = true
                  Prefs.setSetupOptionalHidden(context, true)
                }) { Text(stringResource(R.string.setup_later)) }
              }
            }
          }
        }
      }
    }

    if (stale || syncing || syncStatus != null) {
      item {
        Card(
          Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
          ),
        ) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.Filled.CloudDownload, contentDescription = null)
              Text(
                stringResource(
                  when {
                    !stale && !syncing -> R.string.sync_done_title
                    neverSynced -> R.string.sync_never_title
                    else -> R.string.sync_stale_title
                  },
                ),
                style = MaterialTheme.typography.titleMedium,
              )
            }
            if (stale) {
              Text(
                stringResource(R.string.sync_body),
                style = MaterialTheme.typography.bodySmall,
              )
              Button(enabled = !syncing, onClick = startSync) {
                Text(
                  stringResource(
                    if (syncing) R.string.sync_running else R.string.sync_now,
                  ),
                )
              }
            }
            syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
