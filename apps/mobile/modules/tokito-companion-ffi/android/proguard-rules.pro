# Keep UniFFI / JNA symbols used via reflection.
-keep class com.sun.jna.** { *; }
-keep class uniffi.tokito_companion_ffi.** { *; }
-dontwarn java.lang.reflect.Method
