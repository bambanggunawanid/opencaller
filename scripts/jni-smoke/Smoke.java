import dev.opencaller.app.NativeCore;

/**
 * JNI seam smoke test against the host-built cdylib and the exact assets
 * bundled in the APK. Exercises the same call sequence DbManager performs:
 * verify signature → open → count → hot-path lookups → close.
 *
 * Run via scripts/jni-smoke.sh. Exits non-zero on any failure.
 */
public final class Smoke {
  private static void check(boolean ok, String what) {
    System.out.println((ok ? "  OK  " : "  FAIL") + " " + what);
    if (!ok) System.exit(1);
  }

  public static void main(String[] args) {
    String soPath = args[0];
    String assets = args[1];
    System.load(soPath);

    String shard = assets + "/us.ocdb";
    String sig = assets + "/us.ocdb.sig";
    String pub = assets + "/shard_signing.pub";

    check(NativeCore.nativeVerify(shard, sig, pub), "signature verifies");
    check(!NativeCore.nativeVerify(shard, pub, pub), "wrong signature rejected");

    long handle = NativeCore.nativeOpen(shard);
    check(handle != 0, "database opens");
    long count = NativeCore.nativeEntryCount(handle);
    check(count > 9000, "entry count sane (" + count + ")");

    // A number present in the 2026-08-26 FTC data (11-digit NANP form).
    String hit = NativeCore.nativeLookup(handle, "+18283003919");
    check(hit != null && hit.startsWith("debt-collection|"), "known spam number found: " + hit);

    // National 10-digit form must be handled by the Kotlin fallback, which
    // prepends country code 1 — simulate both candidates here.
    check(NativeCore.nativeLookup(handle, "8283003919") == null, "national form misses raw (Kotlin adds the 1-prefix)");
    check(NativeCore.nativeLookup(handle, "18283003919") != null, "national form hits with 1-prefix");

    check(NativeCore.nativeLookup(handle, "+15550000000") == null, "unknown number misses");
    check(NativeCore.nativeLookup(handle, "not a number") == null, "garbage input safe");

    // Hot-path timing: 100k lookups through the JNI boundary.
    long t0 = System.nanoTime();
    int found = 0;
    for (int i = 0; i < 100_000; i++) {
      if (NativeCore.nativeLookup(handle, "+15550000000") != null) found++;
    }
    long nsPerMiss = (System.nanoTime() - t0) / 100_000;
    check(found == 0, "timing loop consistent");
    System.out.println("  ----  JNI miss lookup: " + nsPerMiss + " ns avg (screening budget 50 ms)");
    check(nsPerMiss < 1_000_000, "under 1 ms through JNI");

    NativeCore.nativeClose(handle);

    // ---- F7 heuristics (stateless, no handle) ----
    check("own-number-spoof".equals(NativeCore.nativeHeuristic("18283003919", "+1 828 300 3919")),
        "heuristic: own-number spoof detected");
    check("neighbor-spoof".equals(NativeCore.nativeHeuristic("18283001234", "18283003919")),
        "heuristic: neighbor spoof detected");
    check("invalid-number".equals(NativeCore.nativeHeuristic("+1 911 123 4567", "")),
        "heuristic: invalid NANP detected");
    check(NativeCore.nativeHeuristic("18283003919", "") == null,
        "heuristic: clean number passes");

    // ---- Update transaction matrix (args[2] = dir prepared by the shell
    // script: v1/v2/vOld shards + sigs + throwaway pubkey) ----
    if (args.length > 2) updateScenario(args[2]);

    System.out.println("ALL PASSED");
  }

  private static byte[] read(String p) throws java.io.IOException {
    return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(p));
  }

  private static void updateScenario(String dir) {
    try {
      String pub = dir + "/shard_signing.pub";

      // Fresh install of v1.
      String r = NativeCore.nativeApplyUpdate(dir, "us.ocdb", read(dir + "/v1.ocdb"), read(dir + "/v1.ocdb.sig"), pub);
      check(r.startsWith("ok|"), "update: fresh install applies (" + r + ")");
      long h = NativeCore.nativeOpen(dir + "/us.ocdb");
      int v1days = NativeCore.nativeBuiltDays(h);
      NativeCore.nativeClose(h);

      // Upgrade to v2 (newer built date).
      r = NativeCore.nativeApplyUpdate(dir, "us.ocdb", read(dir + "/v2.ocdb"), read(dir + "/v2.ocdb.sig"), pub);
      check(r.startsWith("ok|"), "update: upgrade applies (" + r + ")");
      h = NativeCore.nativeOpen(dir + "/us.ocdb");
      check(NativeCore.nativeBuiltDays(h) > v1days, "update: built_days advanced");
      NativeCore.nativeClose(h);

      // Replay of the older shard: valid signature, must be refused.
      r = NativeCore.nativeApplyUpdate(dir, "us.ocdb", read(dir + "/vOld.ocdb"), read(dir + "/vOld.ocdb.sig"), pub);
      check(r.startsWith("error|rollback"), "update: rollback refused (" + r + ")");

      // Tampered bytes: refused, installed shard still opens.
      byte[] evil = read(dir + "/v2.ocdb");
      evil[40] ^= 0xFF;
      r = NativeCore.nativeApplyUpdate(dir, "us.ocdb", evil, read(dir + "/v2.ocdb.sig"), pub);
      check(r.startsWith("error|signature"), "update: tamper refused (" + r + ")");
      h = NativeCore.nativeOpen(dir + "/us.ocdb");
      check(h != 0 && NativeCore.nativeEntryCount(h) > 0, "update: installed shard intact after refusals");
      NativeCore.nativeClose(h);
    } catch (java.io.IOException e) {
      check(false, "update scenario io: " + e.getMessage());
    }
  }

  private Smoke() {}
}
