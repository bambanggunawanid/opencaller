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
   * Base URL of the published shards (GitHub Releases / CDN — PRD §11).
   * Blank until the release channel exists; the UI degrades gracefully.
   */
  const val UPDATE_BASE_URL = ""

  private const val MAX_SHARD_BYTES = 256L * 1024 * 1024

  /** Blocking; call off the main thread. Returns a user-displayable line. */
  fun checkAndApply(context: Context): String {
    if (UPDATE_BASE_URL.isBlank()) {
      return "Updates not configured yet (no release channel)"
    }
    return try {
      val shard = fetch("$UPDATE_BASE_URL/${DbManager.SHARD}")
      val sig = fetch("$UPDATE_BASE_URL/${DbManager.SHARD}.sig")
      val result = NativeCore.nativeApplyUpdate(
        context.filesDir.absolutePath,
        DbManager.SHARD,
        shard,
        sig,
        java.io.File(context.filesDir, DbManager.PUBKEY).absolutePath,
      )
      val parts = result.split('|')
      when {
        parts[0] == "ok" -> {
          DbManager.reload(context)
          "Updated: ${parts[1]} numbers (built day ${parts[2]})"
        }
        else -> "Update refused: ${parts.getOrElse(1) { "unknown" }}"
      }
    } catch (e: Exception) {
      "Update failed: ${e.message}"
    }
  }

  private fun fetch(url: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 60_000
    try {
      if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
      if (conn.contentLengthLong > MAX_SHARD_BYTES) error("shard too large")
      return conn.inputStream.use { it.readBytes() }
    } finally {
      conn.disconnect()
    }
  }
}
