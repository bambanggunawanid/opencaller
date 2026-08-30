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

  /** Blocking; call off the main thread. Returns a user-displayable line. */
  fun checkAndApply(context: Context): String {
    if (UPDATE_BASE_URL.isBlank()) {
      return L10n.str(context, R.string.update_not_configured)
    }
    val results = Prefs.enabledCountries(context).map { cc ->
      "${cc.uppercase()}: ${updateCountry(context, cc)}"
    }
    DbManager.reload(context)
    return results.joinToString("\n")
  }

  private fun updateCountry(context: Context, country: String): String {
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
          L10n.str(context, R.string.update_ok, parts[1], built)
        }
        else ->
          L10n.str(context, R.string.update_refused, parts.getOrElse(1) { "unknown" })
      }
    } catch (e: Exception) {
      L10n.str(context, R.string.update_failed, e.message ?: e.javaClass.simpleName)
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
