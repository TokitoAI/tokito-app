# Keep UniFFI / JNA symbols used via reflection.
-keep class com.sun.jna.** { *; }
-keep class uniffi.tokito_companion_ffi.** { *; }
# JNA's desktop AWT adapters are not used by its Android native mapping.
# Upstream Android guidance: https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md#jna-on-android
# Keep this exception limited to the four absent desktop types reported by R8.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
