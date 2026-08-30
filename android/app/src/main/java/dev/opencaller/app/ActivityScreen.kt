package dev.opencaller.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Tab 2 — the local screening history, rendered for humans. */
@Composable
fun ActivityScreen() {
  val context = LocalContext.current
  val events = remember { DbManager.recentEvents(context) }

  LazyColumn(
    Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Text(
        stringResource(R.string.activity_title),
        style = MaterialTheme.typography.headlineMedium,
      )
    }
    item {
      Text(
        stringResource(R.string.activity_subtitle),
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (events.isEmpty()) {
      item {
        Text(
          stringResource(R.string.activity_empty),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 24.dp),
        )
      }
    }
    items(events) { e -> EventRow(e) }
  }
}

private class EventUi(
  val icon: ImageVector,
  val label: String,
  val detail: String?,
  val alert: Boolean, // tint the icon with the error color
)

/**
 * Decode the pipe-file verdict vocabulary (ScreeningService +
 * CallNotificationListener) into icon/label/detail.
 */
private fun eventUi(context: android.content.Context, verdict: String): EventUi {
  val parts = verdict.split(':')
  fun rest(from: Int) = parts.drop(from).joinToString(":")
    .takeIf { it.isNotEmpty() }?.let { Notifier.friendly(context, it) }
  fun s(resId: Int, vararg args: Any) = L10n.str(context, resId, *args)
  return when (parts[0]) {
    "reject" -> EventUi(Icons.Filled.Block, s(R.string.ev_blocked), rest(1), alert = true)
    "silence" -> EventUi(Icons.Filled.VolumeOff, s(R.string.ev_silenced), rest(1), alert = true)
    "muted" -> EventUi(
      Icons.Filled.NotificationsOff,
      s(R.string.ev_muted_sms),
      rest(2),
      alert = true,
    )
    "warned" -> EventUi(
      Icons.Filled.Warning,
      s(R.string.ev_warned, parts.getOrElse(1) { "?" }),
      rest(2),
      alert = true,
    )
    "allowed" -> EventUi(
      Icons.Filled.Warning,
      s(R.string.ev_flagged_allowed),
      rest(1),
      alert = false,
    )
    "learned" -> EventUi(
      Icons.Filled.Lightbulb,
      s(R.string.ev_learned),
      s(R.string.ev_learned_detail, parts.getOrElse(2) { "?" }),
      alert = false,
    )
    "unknown" -> EventUi(Icons.Filled.Call, s(R.string.ev_unknown), null, alert = false)
    else -> EventUi(Icons.Filled.BugReport, verdict, null, alert = false)
  }
}

private fun timeLabel(atMillis: Long): String {
  val zone = ZoneId.systemDefault()
  val at = Instant.ofEpochMilli(atMillis).atZone(zone)
  return if (at.toLocalDate() == LocalDate.now(zone))
    at.format(DateTimeFormatter.ofPattern("HH:mm"))
  else at.format(DateTimeFormatter.ofPattern("d MMM"))
}

/** One history line; shared with the Shield tab's "Recent" preview. */
@Composable
fun EventRow(e: ScreenEvent) {
  val ui = eventUi(LocalContext.current, e.verdict)
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      ui.icon,
      contentDescription = null,
      tint = if (ui.alert) MaterialTheme.colorScheme.error
      else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.weight(1f)) {
      Text(e.number, style = MaterialTheme.typography.bodyLarge)
      Text(
        ui.detail?.let { "${ui.label} — $it" } ?: ui.label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      if (e.atMillis > 0) timeLabel(e.atMillis) else "",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
