package dev.tokito.physical.bridge

import android.content.Intent
import android.os.Build
import ai.tokito.physical.runtime.PhysicalRuntime
import ai.tokito.physical.runtime.RuntimeConfiguration
import dev.tokito.physical.MainActivity
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Thin control-plane boundary for the native Physical experience.
 *
 * Camera frames, microphone samples, tracking, tensors, model state, and
 * inference results stay inside [MainActivity] and its native runtimes. The
 * Expo layer receives only session identity and low-rate lifecycle metadata.
 */
class TokitoPhysicalModule : Module() {
  private val physicalRuntime by lazy {
    PhysicalRuntime(
      RuntimeConfiguration(
        contractVersion = "v1",
        deviceProfileId = Build.MODEL.takeUnless(String::isBlank) ?: "android-device",
      ),
    )
  }

  override fun definition() = ModuleDefinition {
    Name("TokitoPhysical")

    AsyncFunction("capabilities") {
      val handshake = physicalRuntime.capabilities()
      mapOf(
        "contractVersion" to handshake.contractVersion,
        "deviceProfile" to handshake.deviceProfile,
        "operations" to handshake.capabilities.map { it.operation },
      )
    }

    AsyncFunction("openSession") {
      val context = appContext.currentActivity
        ?: throw CodedException("NoActivity", "Tokito must be visible to open a Physical session", null)
      val session = physicalRuntime.openSession()
      context.startActivity(Intent(context, MainActivity::class.java))
      mapOf("id" to session.id, "state" to "created")
    }
  }
}
