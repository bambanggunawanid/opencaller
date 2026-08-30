# JNI entry points: the Rust cdylib resolves these by exact name at runtime;
# R8 must neither rename nor strip them.
-keep class dev.opencaller.app.NativeCore { *; }
