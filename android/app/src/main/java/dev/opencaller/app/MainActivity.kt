package dev.opencaller.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    DbManager.ensureOpen(this)
    UpdateScheduler.sync(this)
    setContent { OpenCallerTheme { OpenCallerApp() } }
  }
}

/** Material You: follow the system palette and dark mode (API 31+). */
@Composable
fun OpenCallerTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val context = LocalContext.current
  val scheme = when {
    Build.VERSION.SDK_INT >= 31 ->
      if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
  }
  MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String, val icon: ImageVector) {
  SHIELD("Shield", Icons.Filled.Shield),
  ACTIVITY("Activity", Icons.Filled.History),
  RULES("Rules", Icons.Filled.Block),
  SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun OpenCallerApp() {
  val context = LocalContext.current
  var tab by rememberSaveable { mutableIntStateOf(0) }

  // F1 warnings need POST_NOTIFICATIONS on Android 13+.
  val notifLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) {}
  LaunchedEffect(Unit) {
    Notifier.ensureChannel(context)
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  Scaffold(
    bottomBar = {
      NavigationBar {
        Tab.entries.forEachIndexed { i, t ->
          NavigationBarItem(
            selected = tab == i,
            onClick = { tab = i },
            icon = { Icon(t.icon, contentDescription = t.label) },
            label = { Text(t.label) },
          )
        }
      }
    },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (Tab.entries[tab]) {
        Tab.SHIELD -> ShieldScreen(onOpenActivity = { tab = Tab.ACTIVITY.ordinal })
        Tab.ACTIVITY -> ActivityScreen()
        Tab.RULES -> RulesScreen()
        Tab.SETTINGS -> SettingsScreen()
      }
    }
  }
}
