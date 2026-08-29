package dev.opencaller.app

import android.content.Context
import java.io.File

/** A resolved lookup on the screening hot path. */
data class SpamHit(val category: String, val reportCount: Int, val lastSeenDays: Int)

/** One line of local screening history (never leaves the device). */
data class ScreenEvent(val number: String, val verdict: String, val atMillis: Long)

/**
 * Owns the single OCDB handle. On first run the bundled shard is copied out
 * of assets, **signature-verified against the pinned public key**, and only
 * then opened — an unverified shard is treated as absent (PRD §9: the app
 * refuses tampered databases; there is no unverified fallback).
 */
object DbManager {
  private const val SHARD = "us.ocdb"
  private const val SIG = "us.ocdb.sig"
  private const val PUBKEY = "shard_signing.pub"
  private const val HISTORY = "screening_history.log"
  private const val HISTORY_MAX_LINES = 200

  @Volatile private var handle: Long = 0
  @Volatile var verified: Boolean = false
    private set

  @Synchronized
  fun ensureOpen(context: Context): Boolean {
    if (handle != 0L) return true
    val dir = context.filesDir
    for (name in listOf(SHARD, SIG, PUBKEY)) {
      val f = File(dir, name)
      if (!f.exists()) {
        context.assets.open(name).use { input ->
          f.outputStream().use { input.copyTo(it) }
        }
      }
    }
    val shard = File(dir, SHARD)
    verified = NativeCore.nativeVerify(
      shard.absolutePath,
      File(dir, SIG).absolutePath,
      File(dir, PUBKEY).absolutePath,
    )
    if (!verified) return false
    handle = NativeCore.nativeOpen(shard.absolutePath)
    return handle != 0L
  }

  fun entryCount(): Long = if (handle != 0L) NativeCore.nativeEntryCount(handle) else 0

  /** Hot path: called from the CallScreeningService while the call rings. */
  fun lookup(context: Context, number: String): SpamHit? {
    if (!ensureOpen(context)) return null
    val raw = NativeCore.nativeLookup(handle, number) ?: return null
    val parts = raw.split('|')
    if (parts.size != 3) return null
    return SpamHit(
      category = parts[0],
      reportCount = parts[1].toIntOrNull() ?: 0,
      lastSeenDays = parts[2].toIntOrNull() ?: 0,
    )
  }

  /** Append to the app's own history file (not the system call log). */
  fun logEvent(context: Context, number: String, verdict: String) {
    val f = File(context.filesDir, HISTORY)
    val lines = (if (f.exists()) f.readLines() else emptyList())
      .takeLast(HISTORY_MAX_LINES - 1)
      .toMutableList()
    lines.add("${System.currentTimeMillis()}|$number|$verdict")
    f.writeText(lines.joinToString("\n"))
  }

  fun recentEvents(context: Context): List<ScreenEvent> {
    val f = File(context.filesDir, HISTORY)
    if (!f.exists()) return emptyList()
    return f.readLines().mapNotNull { line ->
      val p = line.split('|')
      if (p.size != 3) return@mapNotNull null
      ScreenEvent(number = p[1], verdict = p[2], atMillis = p[0].toLongOrNull() ?: 0)
    }.asReversed()
  }
}
