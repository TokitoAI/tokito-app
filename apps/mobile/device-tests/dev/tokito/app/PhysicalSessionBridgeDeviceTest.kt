package dev.tokito.app

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.DynamicFromObject
import com.facebook.react.bridge.ReadableNativeMap
import dev.tokito.physical.session.PhysicalSessionContext
import dev.tokito.physical.session.PhysicalSessionInput
import expo.modules.kotlin.types.TypeConverterProviderImpl
import expo.modules.kotlin.types.descriptors.RawTypeDescriptor
import expo.modules.kotlin.types.descriptors.TypeDescriptor
import org.junit.Assert.*
import org.junit.Test

/** Real React Native map + Expo collection conversion in the optimized installed target. */
class PhysicalSessionBridgeDeviceTest {
    private fun fields(vararg values: Any?): Map<String, Any?> {
        val result = java.util.LinkedHashMap<String, Any?>()
        var index = 0
        while (index < values.size) {
            result[values[index] as String] = values[index + 1]
            index += 2
        }
        return result
    }

    private fun convert(input: Map<String, Any?>?): PhysicalSessionContext {
        // Match the shipping nullable Map<String, Any?> descriptor without
        // requiring test-owned Pika compilation or Kotlin reflection metadata.
        val parameters = java.util.ArrayList<RawTypeDescriptor>()
        parameters.add(RawTypeDescriptor.Simple(String::class.java, false, null))
        parameters.add(RawTypeDescriptor.Simple(Any::class.java, true, null))
        val descriptor = TypeDescriptor(RawTypeDescriptor.Parameterized(Map::class.java, true, null, parameters)) {
            error("Map conversion must not require Kotlin reflective type reconstruction")
        }
        val converter = TypeConverterProviderImpl.obtainTypeConverter(descriptor)
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
        assertEquals(PhysicalSessionContext(), convert(fields()))
        assertEquals(PhysicalSessionContext(), convert(fields("projectId", null, "designId", null, "contextRevision", null)))
        val project = "opaque/project-α"
        val design = "design:radio"
        val revision = "immutable:revision"
        assertEquals(PhysicalSessionContext(project), convert(fields("projectId", project)))
        assertEquals(PhysicalSessionContext(project, design, revision), convert(fields(
            "projectId", project, "designId", design, "contextRevision", revision,
        )))
    }

    @Test fun optimizedExpoMapRejectsMalformedContextInsteadOfCastingOrDefaulting() {
        listOf(
            fields("unknown", "x"), fields("projectId", 42), fields("projectId", false),
            fields("projectId", listOf("nested")), fields("projectId", fields("nested", "x")),
            fields("projectId", " "), fields("projectId", "bad\nidentifier"),
            fields("projectId", "x".repeat(PhysicalSessionInput.MAX_VALUE_BYTES + 1)),
            fields("designId", "unbound"), fields("contextRevision", "unbound"),
        ).forEach { input ->
            assertTrue("Malformed context must fail", runCatching { convert(input) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
