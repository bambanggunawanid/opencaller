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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

/** Tab 3 — personal block/allow rules (F5). Private to this phone. */
@Composable
fun RulesScreen() {
  val context = LocalContext.current
  var ruleInput by rememberSaveable { mutableStateOf("") }
  var blockRules by remember { mutableStateOf(Prefs.blockRules(context).sorted()) }
  var allowRules by remember { mutableStateOf(Prefs.allowRules(context).sorted()) }

  LazyColumn(
    Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { Text("Your rules", style = MaterialTheme.typography.headlineMedium) }
    item {
      Text(
        "Rules are private to this phone. They never leave your device and " +
          "never mark anyone in the shared database — blocking is a " +
          "preference, not a report.",
        style = MaterialTheme.typography.bodySmall,
      )
    }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = ruleInput,
          onValueChange = { ruleInput = it },
          label = { Text("Number, sender name, or prefix (* suffix)") },
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = {
            if (Prefs.addRule(context, block = true, ruleInput)) {
              blockRules = Prefs.blockRules(context).sorted()
              ruleInput = ""
            }
          }) { Text("Block") }
          OutlinedButton(onClick = {
            if (Prefs.addRule(context, block = false, ruleInput)) {
              allowRules = Prefs.allowRules(context).sorted()
              ruleInput = ""
            }
          }) { Text("Always allow") }
        }
      }
    }

    if (blockRules.isNotEmpty()) {
      item {
        Text(
          "Blocked (${blockRules.size})",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      items(blockRules) { rule ->
        RuleRow(rule, blocked = true) {
          Prefs.removeRule(context, block = true, rule)
          blockRules = Prefs.blockRules(context).sorted()
        }
      }
    }
    if (allowRules.isNotEmpty()) {
      item {
        Text(
          "Always allowed (${allowRules.size})",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      items(allowRules) { rule ->
        RuleRow(rule, blocked = false) {
          Prefs.removeRule(context, block = false, rule)
          allowRules = Prefs.allowRules(context).sorted()
        }
      }
    }

    item {
      Text(
        "Heads-up: Android never sends calls from saved contacts to " +
          "screening apps, so rules can't fire for them. For an annoying " +
          "auto-saved number (e.g. your operator): unsave or hide it " +
          "(Contacts → Contacts to display → uncheck SIM), or block it in " +
          "the Phone app — the system blocklist works even for contacts.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

@Composable
private fun RuleRow(rule: String, blocked: Boolean, onRemove: () -> Unit) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      if (blocked) Icons.Filled.Close else Icons.Filled.CheckCircle,
      contentDescription = null,
      tint = if (blocked) MaterialTheme.colorScheme.error
      else MaterialTheme.colorScheme.primary,
    )
    Text(rule, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    IconButton(onClick = onRemove) {
      Icon(Icons.Filled.Delete, contentDescription = "Remove rule")
    }
  }
}
