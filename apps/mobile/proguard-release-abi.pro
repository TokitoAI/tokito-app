# Stable cross-APK acceptance boundary. The release and instrumentation APKs
# are separate R8 programs. Applying mapping.txt rewrites names, but cannot
# reconstruct optimized parameter order, narrowed types or removed entrypoints.
# Keep only the runtime exports exercised by the shipping device acceptance
# suite. Production shrinking/optimization stays enabled for everything else.
-keep class kotlin.jvm.internal.Intrinsics { *; }
# AndroidJUnitRunner calls this shared production dependency before any test.
# The test APK excludes target dependencies even when target R8 removes them.
-keep class androidx.tracing.Trace { *; }

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
-keep,includedescriptorclasses class dev.tokito.physical.session.PhysicalSessionInput { public *; }

# Optimized-device acceptance exercises the real Expo collection conversion ABI.
-keep,includedescriptorclasses class expo.modules.kotlin.types.TypeConverterProviderImpl { public *; }
-keep,includedescriptorclasses interface expo.modules.kotlin.types.TypeConverter { public *; }
-keep,includedescriptorclasses interface expo.modules.kotlin.types.descriptors.RawTypeDescriptor { public *; }
-keep,includedescriptorclasses class expo.modules.kotlin.types.descriptors.RawTypeDescriptor$Simple { public *; }
-keep,includedescriptorclasses class expo.modules.kotlin.types.descriptors.RawTypeDescriptor$Parameterized { public *; }
-keep,includedescriptorclasses class expo.modules.kotlin.types.descriptors.TypeDescriptor { public *; }
-keep,includedescriptorclasses class com.facebook.react.bridge.DynamicFromObject { public *; }
-keep,includedescriptorclasses class com.facebook.react.bridge.Arguments { public *; }

# Reviewed exports from release-test-abi.py's paired DEX audit, not a package
# whitelist. Kotlin multifile facades inherit static API from their named
# delegate family; preserving only the facade leaves inherited methods unsafe.
-keep,includedescriptorclasses class kotlin.LazyKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.collections.SetsKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.jdk7.AutoCloseableKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.io.FilesKt** { public *; protected *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.Boxing { public *; }
# The target includes this JSON dependency as program classes. Preserve the
# exact two classes consumed by the independently optimized tokenizer tests;
# do not assume their renamed target copies are Android framework exports.
-keep,includedescriptorclasses class org.json.JSONObject { public *; protected *; }
-keep,includedescriptorclasses class org.json.JSONArray { public *; protected *; }
-keep,includedescriptorclasses class kotlin.Lazy { public *; }
-keep,includedescriptorclasses class kotlin.Result$Companion { public *; }
-keep,includedescriptorclasses class kotlin.Result { public *; }
-keep,includedescriptorclasses class kotlin.ResultKt { public *; }
-keep,includedescriptorclasses class kotlin.Unit { public *; }
-keep,includedescriptorclasses class kotlin.coroutines.Continuation { public *; }
-keep,includedescriptorclasses class kotlin.coroutines.CoroutineContext { public *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.SuspendLambda { public *; protected *; }
-keep,includedescriptorclasses class kotlin.enums.EnumEntries { public *; }
-keep,includedescriptorclasses class kotlin.jvm.functions.Function0 { public *; }
-keep,includedescriptorclasses class kotlin.jvm.functions.Function2 { public *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.DefaultConstructorMarker { public *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Lambda { public *; protected *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineScope { public *; }
-keep,includedescriptorclasses class kotlinx.coroutines.flow.StateFlow { public *; }
# Real microphone acceptance consumes final events across the paired test APK.
# Keep the Flow interface and its Kotlin facade/delegate ABI, not all coroutines.
-keep,includedescriptorclasses interface kotlinx.coroutines.flow.Flow { public *; }
-keep,includedescriptorclasses class kotlinx.coroutines.flow.FlowKt** { public *; }

# Installed acceptance exercises these APIs as a separate consumer program.
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.Capability { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DesignBoardTarget { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DesignContextIndexLimits { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DesignContextRecord { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DesignEvidenceLocator { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DesignFactAuthority { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueFailure { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueFailureCode { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueLimits { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueModel { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueModelProvisioner$Companion { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueModelProvisioner { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueOutcome$Response { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueOutcome { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueRequest { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueRuntime { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.DialogueRuntimeState$Ready { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.LocalBenchDialoguePolicy$Answer { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.LocalBenchDialoguePolicy$Authority { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.LocalBenchDialoguePolicy { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.LocalDesignContextIndex { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.LocalTextEmbeddingRuntime { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.LocalTextEmbeddingRuntime$Companion { public *; }
-keep,includedescriptorclasses interface dev.tokito.physical.dialogue.TextEmbeddingEncoder { public *; }
-keep,includedescriptorclasses interface dev.tokito.physical.dialogue.DesignSemanticSearch { public *; }
-keep,includedescriptorclasses interface dev.tokito.physical.dialogue.DesignVectorCache { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.SqliteDesignVectorCache { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.SemanticDesignSearch { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.SemanticDesignMatch { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.dialogue.TokitoDialogueRuntime { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.voice.SpeechQueuePolicy { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.voice.TokitoVoiceRuntime { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.voice.VoiceModelBundle { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.voice.VoiceRuntimeConfig { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.voice.VoiceRuntimeState$Listening { public *; }
-keep,includedescriptorclasses class dev.tokito.physical.voice.VoiceRuntimeState$Ready { public *; }

# Exact Qwen device acceptance reflects only these named entrypoints. No new
# package whitelist or differently optimized QA build is permitted.
-keep class dev.tokito.physical.dialogue.NativeQwenTokenizer {
    public static final dev.tokito.physical.dialogue.NativeQwenTokenizer$Companion Companion;
    public int[] encode(java.lang.String);
    public void close();
    private native <methods>;
}
-keep class dev.tokito.physical.dialogue.NativeQwenTokenizer$Companion {
    public dev.tokito.physical.dialogue.NativeQwenTokenizer open(android.content.Context, java.io.File);
}
-keep class dev.tokito.physical.dialogue.QairtContextMetadata {
    public static final dev.tokito.physical.dialogue.QairtContextMetadata$Companion Companion;
    public int getCapacity();
}
-keep class dev.tokito.physical.dialogue.QairtContextMetadata$Companion {
    public dev.tokito.physical.dialogue.QairtContextMetadata admit(android.content.Context, dev.tokito.physical.dialogue.DialogueModel);
}
-keep class dev.tokito.physical.dialogue.NativeQnnInventory { private native <methods>; }
-keepclassmembers class dev.tokito.physical.MainActivity {
    public *** getLocalReasoningState();
    public java.lang.Object respondLocally(java.lang.String, kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class kotlin.TuplesKt { public *; }
-keepclassmembers class kotlin.Pair {
    public java.lang.Object component1();
    public java.lang.Object component2();
}
-keep,includedescriptorclasses class kotlin.io.CloseableKt { public *; }
-keep,includedescriptorclasses class kotlinx.coroutines.DelayKt { public *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Dispatchers { public *; }
-keep,includedescriptorclasses class kotlinx.coroutines.MainCoroutineDispatcher { public *; }
-keep class dev.tokito.physical.session.PhysicalSessionContext { public *; }
-keep class dev.tokito.physical.session.PhysicalSessionRecord { public *; }
-keep class dev.tokito.physical.session.PhysicalSessionStore { public *; }
-keep class dev.tokito.physical.session.PhysicalSessionStore$Companion { public *; }
