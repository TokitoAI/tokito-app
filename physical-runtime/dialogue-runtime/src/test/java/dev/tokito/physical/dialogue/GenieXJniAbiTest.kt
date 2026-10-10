package dev.tokito.physical.dialogue

import org.junit.Test

class GenieXJniAbiTest {
    @Test fun pinnedSdkContainsRequiredNativeFieldsAndEntrypoint() {
        // No native creation or model inference: validates the shipped SDK ABI.
        requireGenieXJniAbi()
    }
}
