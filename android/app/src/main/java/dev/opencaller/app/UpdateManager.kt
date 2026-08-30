package dev.opencaller.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * The distribution leg (PRD F4): fetch a newer signed shard from static
 * hosting and hand it to the Rust core's atomic update transaction.
 *
 * PRD §5 promise 4: this is the app's ONLY network activity, and it is
 * user-visible and user-triggered (manual button in M0; WorkManager
 * scheduling with a Wi-Fi-only/off setting arrives with F4 UI). The pinned
 * public key means the transport does not need to be trusted — a hostile
 * CDN can only serve shards that verify or get rejected.
 */
object UpdateManager {
  /**
   * Base URL of the published shards (PRD §11): the repo's latest release.
   * The transport is untrusted — only the Ed25519 signature matters.
   */
  const val UPDATE_BASE_URL =
    "https://github.com/bambanggunawanid/opencaller/releases/latest/download"

  private const val MAX_SHARD_BYTES = 64L * 1024 * 1024

  /** Shard age (vs. today) after which the UI nags and auto-sync kicks in. */
  private const val STALE_AFTER_DAYS = 10L

  /** Don't retry a failed launch-time auto-sync more than every 6 hours. */
  private const val AUTO_SYNC_RETRY_MS = 6L * 60 * 60 * 1000

  fun neverSynced(context: Context): Boolean = Prefs.lastSyncMillis(context) == 0L

  /**
   * True when the on-device list deserves a refresh: never synced from the
   * network (a fresh install runs on the small bundled seed — the weekly
   * job's first run is up to 7 days away), or the newest shard is older
   * than [STALE_AFTER_DAYS].
   */
  fun isStale(context: Context): Boolean {
    if (neverSynced(context)) return true
    val builtDays = DbManager.builtDays()
    if (builtDays <= 0) return true
    return java.time.LocalDate.now().toEpochDay() - builtDays >= STALE_AFTER_DAYS
  }

  /**
   * Launch-time auto-sync gate: stale, updates not OFF, a usable network
   * for the chosen mode, and no attempt in the last 6 hours. The weekly
   * WorkManager job stays the background path; this covers the fresh
   * install and the phone that was off on update day.
   */
  fun shouldAutoSync(context: Context): Boolean {
    if (!isStale(context)) return false
    val mode = Prefs.updateMode(context)
    if (mode == Prefs.UpdateMode.OFF) return false
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
      ?: return false
    if (cm.activeNetwork == null) return false
    if (mode == Prefs.UpdateMode.WIFI_ONLY && cm.isActiveNetworkMetered) return false
    return System.currentTimeMillis() - Prefs.lastSyncAttemptMillis(context) >=
      AUTO_SYNC_RETRY_MS
  }

  /** Blocking; call off the main thread. Returns a user-displayable line. */
  fun checkAndApply(context: Context): String {
    if (UPDATE_BASE_URL.isBlank()) {
      return L10n.str(context, R.string.update_not_configured)
    }
    var anyOk = false
    val results = Prefs.enabledCountries(context).map { cc ->
      val (ok, msg) = updateCountry(context, cc)
      if (ok) anyOk = true
      "${cc.uppercase()}: $msg"
    }
    if (anyOk) Prefs.setLastSyncMillis(context, System.currentTimeMillis())
    DbManager.reload(context)
    return results.joinToString("\n")
  }

  private fun updateCountry(context: Context, country: String): Pair<Boolean, String> {
    val name = DbManager.shardName(country)
    return try {
      val shard = fetch("$UPDATE_BASE_URL/$name")
      val sig = fetch("$UPDATE_BASE_URL/$name.sig")
      val result = NativeCore.nativeApplyUpdate(
        context.filesDir.absolutePath,
        name,
        shard,
        sig,
        java.io.File(context.filesDir, DbManager.PUBKEY).absolutePath,
      )
      val parts = result.split('|')
      when {
        parts[0] == "ok" -> {
          val built = parts[2].toLongOrNull()
            ?.let { java.time.LocalDate.ofEpochDay(it).toString() } ?: parts[2]
          true to L10n.str(context, R.string.update_ok, parts[1], built)
        }
        else ->
          false to L10n.str(context, R.string.update_refused, parts.getOrElse(1) { "unknown" })
      }
    } catch (e: Exception) {
      false to L10n.str(context, R.string.update_failed, e.message ?: e.javaClass.simpleName)
    }
  }

  private fun fetch(url: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 60_000
    try {
      if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
      // Bounded read: Content-Length is attacker-controlled/optional
      // (chunked responses omit it), so enforce the cap on actual bytes.
      conn.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          total += n
          if (total > MAX_SHARD_BYTES) error("download exceeds size limit")
          out.write(buf, 0, n)
        }
        return out.toByteArray()
      }
    } finally {
      conn.disconnect()
    }
  }
}
