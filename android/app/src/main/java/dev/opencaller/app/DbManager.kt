package dev.opencaller.app

import android.content.Context
import java.io.File

/** A resolved lookup on the screening hot path. */
data class SpamHit(val category: String, val reportCount: Int, val lastSeenDays: Int)

/** One line of local screening history (never leaves the device). */
data class ScreenEvent(val number: String, val verdict: String, val atMillis: Long)

/** Status of one opened country shard, for the UI. */
data class ShardInfo(val country: String, val entries: Long, val builtDays: Int)

/**
 * Owns one OCDB handle per enabled country (F3). Every shard is
 * **signature-verified against the pinned public key** before opening —
 * an unverified shard is treated as absent (PRD §9; no unverified
 * fallback). Bundled countries self-heal from assets; downloaded-only
 * countries are deleted when corrupt and re-fetched by the next update.
 */
object DbManager {
  const val PUBKEY = "shard_signing.pub"
  private const val HISTORY = "screening_history.log"
  private const val HISTORY_MAX_LINES = 200

  /** Countries whose shard ships inside the APK as a first-run seed. */
  private val BUNDLED = setOf("us")

  /** Dial codes for lookup candidates of 0-prefixed national numbers. */
  private val DIAL_CODES = mapOf(
    "us" to "1", "ca" to "1", "in" to "91", "id" to "62",
    "gb" to "44", "au" to "61", "de" to "49", "fr" to "33",
  )

  private val handles = LinkedHashMap<String, Long>() // country -> handle

  fun shardName(country: String) = "$country.ocdb"

  @Synchronized
  fun ensureOpen(context: Context): Boolean {
    val enabled = Prefs.enabledCountries(context)
    copyAsset(context, PUBKEY, overwrite = false)

    val disabled = handles.keys.filter { it !in enabled }
    for (cc in disabled) {
      handles.remove(cc)?.let { NativeCore.nativeClose(it) }
    }
    for (cc in enabled) {
      if (cc !in handles) openShard(context, cc)
    }
    return handles.isNotEmpty()
  }

  /** Close and reopen everything — called after an update swap. */
  @Synchronized
  fun reload(context: Context): Boolean {
    for (h in handles.values) NativeCore.nativeClose(h)
    handles.clear()
    return ensureOpen(context)
  }

  val verified: Boolean
    get() = handles.isNotEmpty()

  fun shardInfos(): List<ShardInfo> = synchronized(this) {
    handles.map { (cc, h) ->
      ShardInfo(cc, NativeCore.nativeEntryCount(h), NativeCore.nativeBuiltDays(h))
    }
  }

  fun entryCount(): Long = shardInfos().sumOf { it.entries }

  fun builtDays(): Int = shardInfos().minOfOrNull { it.builtDays } ?: 0

  private fun openShard(context: Context, cc: String) {
    val name = shardName(cc)
    val shard = File(context.filesDir, name)
    val sig = File(context.filesDir, "$name.sig")

    if ((!shard.exists() || !sig.exists()) && cc in BUNDLED) copyBundled(context, cc)
    if (!shard.exists() || !sig.exists()) return // downloaded by next update

    if (!verifyOnDisk(context, name)) {
      if (cc in BUNDLED) {
        // Self-heal: restore the known-good bundled seed.
        copyBundled(context, cc)
        if (!verifyOnDisk(context, name)) return
      } else {
        // Corrupt download: remove it; UpdateManager re-fetches later.
        shard.delete()
        sig.delete()
        return
      }
    }
    val handle = NativeCore.nativeOpen(shard.absolutePath)
    if (handle != 0L) handles[cc] = handle
  }

  private fun copyBundled(context: Context, cc: String) {
    copyAsset(context, shardName(cc), overwrite = true)
    copyAsset(context, "${shardName(cc)}.sig", overwrite = true)
  }

  private fun copyAsset(context: Context, name: String, overwrite: Boolean) {
    val f = File(context.filesDir, name)
    if (!overwrite && f.exists()) return
    context.assets.open(name).use { input ->
      f.outputStream().use { input.copyTo(it) }
    }
  }

  private fun verifyOnDisk(context: Context, name: String): Boolean {
    val dir = context.filesDir
    return NativeCore.nativeVerify(
      File(dir, name).absolutePath,
      File(dir, "$name.sig").absolutePath,
      File(dir, PUBKEY).absolutePath,
    )
  }

  /**
   * Dial-format candidates for a raw caller ID: as-is; NANP 1-prefix for
   * bare 10-digit numbers; and dial-code forms of 0-prefixed national
   * numbers for each enabled country (proper libphonenumber-grade
   * normalization is future work).
   */
  private fun candidates(number: String, countries: Set<String>): List<String> {
    val digits = number.filter { it.isDigit() }
    if (digits.isEmpty()) return emptyList()
    val out = linkedSetOf(digits)
    if (digits.length == 10 && !digits.startsWith("0")) out.add("1$digits")
    if (digits.startsWith("0") && digits.length >= 9) {
      for (cc in countries) {
        DIAL_CODES[cc]?.let { out.add(it + digits.drop(1)) }
      }
    }
    return out.toList()
  }

  /** Hot path: called from the CallScreeningService while the call rings. */
  fun lookup(context: Context, number: String): SpamHit? {
    if (!ensureOpen(context)) return null
    val cands = candidates(number, Prefs.enabledCountries(context))
    synchronized(this) {
      for (candidate in cands) {
        for (handle in handles.values) {
          val raw = NativeCore.nativeLookup(handle, candidate) ?: continue
          val parts = raw.split('|')
          if (parts.size != 3) continue
          return SpamHit(
            category = parts[0],
            reportCount = parts[1].toIntOrNull() ?: 0,
            lastSeenDays = parts[2].toIntOrNull() ?: 0,
          )
        }
      }
    }
    return null
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
