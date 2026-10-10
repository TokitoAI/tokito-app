# Stable cross-APK acceptance boundary. The release and instrumentation APKs
# are separate R8 programs. Applying mapping.txt rewrites names, but cannot
# reconstruct optimized parameter order, narrowed types or removed entrypoints.
# Keep only the runtime exports exercised by the shipping device acceptance
# suite. Production shrinking/optimization stays enabled for everything else.
-keep class kotlin.jvm.internal.Intrinsics { *; }

-keep,includedescriptorclasses class kotlinx.coroutines.BuildersKt {
    public static java.lang.Object runBlocking(...);
    public static java.lang.Object runBlocking$default(...);
}
-keep,includedescriptorclasses class kotlinx.coroutines.TimeoutKt {
    public static java.lang.Object withTimeout(...);
    public static java.lang.Object withTimeoutOrNull(...);
}

-keep,includedescriptorclasses class dev.tokito.physical.model.ModelBundleRepository { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.model.ModelCapability { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.model.InstalledModelBundle { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.LocalPoseVisionRuntime { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.LocalPoseVisionRuntime$Companion { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.session.PhysicalSessionContext { public *; }
