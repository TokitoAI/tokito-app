package dev.tokito.physical.bridge

import android.content.Intent
import android.os.Build
import android.net.Uri
import ai.tokito.physical.runtime.PhysicalRuntime
import ai.tokito.physical.runtime.RuntimeConfiguration
import dev.tokito.physical.MainActivity
import dev.tokito.physical.session.PhysicalSessionContext as StoredSessionContext
import dev.tokito.physical.session.PhysicalSessionStore
import dev.tokito.physical.model.ModelBundleRepository
import dev.tokito.physical.model.ModelCapability
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class PhysicalSessionContext : Record {
  @Field
  val projectId: String? = null

  @Field
  val designId: String? = null

  @Field
  val contextRevision: String? = null
}

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

    AsyncFunction("openSession") { sessionContext: PhysicalSessionContext? ->
      val activity = appContext.currentActivity
        ?: throw CodedException("NoActivity", "Tokito must be visible to open a Physical session", null)
      val session = physicalRuntime.openSession()
      val storedContext = try {
        StoredSessionContext(
          projectId = sessionContext?.projectId,
          designId = sessionContext?.designId,
          contextRevision = sessionContext?.contextRevision,
        )
      } catch (failure: IllegalArgumentException) {
        throw CodedException("InvalidSessionContext", failure.message ?: "Invalid session context", failure)
      }
      PhysicalSessionStore.open(activity).use { store ->
        store.startSession(storedContext, session.id)
        try {
          activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
              putExtra(MainActivity.EXTRA_SESSION_ID, session.id)
              storedContext.projectId?.let { putExtra(MainActivity.EXTRA_PROJECT_ID, it) }
              storedContext.designId?.let { putExtra(MainActivity.EXTRA_DESIGN_ID, it) }
              storedContext.contextRevision?.let { putExtra(MainActivity.EXTRA_CONTEXT_REVISION, it) }
            },
          )
        } catch (failure: RuntimeException) {
          store.deleteSession(session.id)
          throw failure
        }
      }
      mapOf("id" to session.id, "state" to "created")
    }

    AsyncFunction("pendingSyncSnapshots") {
      val context = appContext.reactContext
        ?: throw CodedException("NoContext", "Tokito is not ready to read Physical sessions", null)
      PhysicalSessionStore.open(context).use { store ->
        store.pendingSyncSnapshots().map { snapshot ->
          mapOf(
            "sessionId" to snapshot.sessionId,
            "projectId" to snapshot.projectId,
            "designId" to snapshot.designId,
            "revision" to snapshot.revision,
            "payloadJson" to snapshot.payloadJson,
          )
        }
      }
    }

    AsyncFunction("markSyncEnqueued") { sessionId: String ->
      val context = appContext.reactContext
        ?: throw CodedException("NoContext", "Tokito is not ready to update Physical sessions", null)
      PhysicalSessionStore.open(context).use { store -> store.markSyncEnqueued(sessionId) }
    }

    AsyncFunction("modelBundles") {
      val context = appContext.reactContext
        ?: throw CodedException("NoContext", "Tokito is not ready to read installed models", null)
      val repository = ModelBundleRepository(context)
      ModelCapability.entries.map { capability ->
        val bundle = repository.activeBundle(capability)
        mapOf(
          "capability" to capability.wireValue,
          "modelId" to bundle?.modelId,
          "modelRevision" to bundle?.modelRevision,
        )
      }
    }

    AsyncFunction("installModelBundle") { capabilityValue: String, uriValue: String ->
      val context = appContext.reactContext
        ?: throw CodedException("NoContext", "Tokito is not ready to install a model", null)
      val capability = try {
        ModelCapability.parse(capabilityValue)
      } catch (failure: IllegalArgumentException) {
        throw CodedException("InvalidCapability", failure.message ?: "Unsupported model capability", failure)
      }
      val uri = Uri.parse(uriValue)
      if (uri.scheme != "content") {
        throw CodedException("InvalidModelUri", "Model bundles must come from Android's document picker", null)
      }
      val installed = run {
        val source = context.contentResolver.openInputStream(uri)
          ?: throw CodedException("ModelUnavailable", "Android could not open the selected model bundle", null)
        source.use { ModelBundleRepository(context).install(capability, it) }
      }
      mapOf(
        "capability" to installed.capability.wireValue,
        "modelId" to installed.modelId,
        "modelRevision" to installed.modelRevision,
      )
    }

    AsyncFunction("rollbackModelBundle") { capabilityValue: String ->
      val context = appContext.reactContext
        ?: throw CodedException("NoContext", "Tokito is not ready to roll back a model", null)
      val capability = try {
        ModelCapability.parse(capabilityValue)
      } catch (failure: IllegalArgumentException) {
        throw CodedException("InvalidCapability", failure.message ?: "Unsupported model capability", failure)
      }
      val installed = run {
        ModelBundleRepository(context).rollback(capability)
      }
      mapOf(
        "capability" to installed.capability.wireValue,
        "modelId" to installed.modelId,
        "modelRevision" to installed.modelRevision,
      )
    }
  }
}
