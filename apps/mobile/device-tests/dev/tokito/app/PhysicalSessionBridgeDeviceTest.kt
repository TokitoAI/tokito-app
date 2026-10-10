package dev.tokito.app

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.DynamicFromObject
import com.facebook.react.bridge.ReadableNativeMap
import dev.tokito.physical.session.PhysicalSessionContext
import dev.tokito.physical.session.PhysicalSessionInput
import expo.modules.kotlin.types.TypeConverterProviderImpl
import expo.modules.kotlin.types.descriptors.typeDescriptorOf
import org.junit.Assert.*
import org.junit.Test

/** Real React Native map + Expo collection conversion in the optimized installed target. */
class PhysicalSessionBridgeDeviceTest {
    private fun convert(input: Map<String, Any?>?): PhysicalSessionContext {
        val converter = TypeConverterProviderImpl.obtainTypeConverter(typeDescriptorOf<Map<String, Any?>?>())
        val nativeMap: Any? = input?.let(Arguments::makeNativeMap)
        if (nativeMap != null) assertTrue(nativeMap is ReadableNativeMap)
        val dynamic = DynamicFromObject(nativeMap)
        try {
            @Suppress("UNCHECKED_CAST")
            val decoded = converter.convert(dynamic, null, false) as Map<String, Any?>?
            // JSI's MAP frontend produces a LinkedHashMap; the legacy bridge
            // supplies a Dynamic ReadableMap. Both must retain the same values.
            val context = PhysicalSessionInput.decode(decoded)
            @Suppress("UNCHECKED_CAST")
            val direct = converter.convert(input, null, false) as Map<String, Any?>?
            assertEquals(context, PhysicalSessionInput.decode(direct))
            return context
        } finally { dynamic.recycle() }
    }

    @Test fun optimizedExpoMapSupportsProjectlessAndOpaqueBoundContexts() {
        assertEquals(PhysicalSessionContext(), convert(null))
        assertEquals(PhysicalSessionContext(), convert(emptyMap()))
        assertEquals(PhysicalSessionContext(), convert(mapOf("projectId" to null, "designId" to null, "contextRevision" to null)))
        val project = "opaque/project-α"
        val design = "design:radio"
        val revision = "immutable:revision"
        assertEquals(PhysicalSessionContext(project), convert(mapOf("projectId" to project)))
        assertEquals(PhysicalSessionContext(project, design, revision), convert(mapOf(
            "projectId" to project, "designId" to design, "contextRevision" to revision,
        )))
    }

    @Test fun optimizedExpoMapRejectsMalformedContextInsteadOfCastingOrDefaulting() {
        listOf(
            mapOf("unknown" to "x"), mapOf("projectId" to 42), mapOf("projectId" to false),
            mapOf("projectId" to listOf("nested")), mapOf("projectId" to mapOf("nested" to "x")),
            mapOf("projectId" to " "), mapOf("projectId" to "bad\nidentifier"),
            mapOf("projectId" to "x".repeat(PhysicalSessionInput.MAX_VALUE_BYTES + 1)),
            mapOf("designId" to "unbound"), mapOf("contextRevision" to "unbound"),
        ).forEach { input ->
            assertTrue("Malformed context must fail", runCatching { convert(input) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
