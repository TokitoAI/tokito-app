# Public Kotlin contract is consumed by the shipping Android application.
-keep class dev.tokito.physical.dialogue.** { *; }
# GenieX 0.3.1 ships no AAR consumer rules. Native code reads private bean
# fields by original name and constructs output/callback objects by JNI class
# identity. These SDK ABI types are not ordinary optimizable Kotlin objects.
-keep,includedescriptorclasses class com.geniex.sdk.bean.** { *; }
-keep,includedescriptorclasses class com.geniex.sdk.jni.** { *; }
