package dev.opencaller.app;

/**
 * Host-JVM twin of the app's NativeCore: same package/class/method names,
 * so the JNI symbols in libopencaller_android.so resolve identically to how
 * they will on-device. Lets CI smoke-test the Kotlin↔Rust seam without an
 * emulator (this VM has no KVM).
 */
public final class NativeCore {
  public static native long nativeOpen(String path);
  public static native String nativeLookup(long handle, String number);
  public static native long nativeEntryCount(long handle);
  public static native int nativeBuiltDays(long handle);
  public static native void nativeClose(long handle);
  public static native boolean nativeVerify(String shardPath, String sigPath, String pubkeyPath);
  public static native String nativeApplyUpdate(
      String dir, String shardName, byte[] newShard, byte[] newSig, String pubkeyPath);

  private NativeCore() {}
}
