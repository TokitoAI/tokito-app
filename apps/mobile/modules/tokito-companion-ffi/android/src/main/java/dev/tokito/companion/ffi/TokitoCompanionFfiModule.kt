package dev.tokito.companion.ffi

import android.util.Base64
import android.os.Build
import android.provider.Settings
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import uniffi.tokito_companion_ffi.CompanionClient
import uniffi.tokito_companion_ffi.CompanionException
import uniffi.tokito_companion_ffi.SyncListener
import uniffi.tokito_companion_ffi.SyncSubscription
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Expo module bridging UniFFI [CompanionClient] into JS.
 * All calls hit Tokito Cloud through Rust core — no stubs.
 *
 * Generated bindings: `uniffi.tokito_companion_ffi` (Mozilla UniFFI Kotlin).
 */
class TokitoCompanionFfiModule : Module() {
  private val subscriptions = ConcurrentHashMap<String, SyncSubscription>()

  override fun definition() = ModuleDefinition {
    Name("TokitoCompanionFfi")
    Events("onSyncStatus", "onSyncBootstrap", "onSyncEvent", "onSyncConflict", "onSyncError")

    OnDestroy {
      subscriptions.values.forEach { it.stop() }
      subscriptions.clear()
    }

    AsyncFunction("baseUrl") { baseUrl: String ->
      CompanionClient(baseUrl).baseUrl()
    }

    AsyncFunction("otpRequest") { baseUrl: String, email: String ->
      runFfi { CompanionClient(baseUrl).otpRequest(email) }
    }

    AsyncFunction("otpVerify") { baseUrl: String, email: String, code: String ->
      runFfi {
        val session = CompanionClient(baseUrl).otpVerify(email, code)
        mapOf(
          "token" to session.token,
          "email" to session.email,
          "plan" to session.plan,
        )
      }
    }

    AsyncFunction("oauthExchange") { baseUrl: String, code: String ->
      runFfi {
        val session = CompanionClient(baseUrl).oauthExchange(code)
        mapOf(
          "token" to session.token,
          "email" to session.email,
          "plan" to session.plan,
        )
      }
    }

    AsyncFunction("me") { baseUrl: String, token: String ->
      runFfi {
        val account = CompanionClient(baseUrl).me(token)
        mapOf(
          "email" to account.email,
          "plan" to account.plan,
        )
      }
    }

    AsyncFunction("listProjects") { baseUrl: String, token: String ->
      runFfi {
        CompanionClient(baseUrl).listProjects(token).map { p ->
          mapOf(
            "id" to p.id,
            "name" to p.name,
            "slug" to p.slug,
            "updated_at" to p.updatedAt,
          )
        }
      }
    }

    AsyncFunction("getProject") { baseUrl: String, token: String, projectId: String ->
      runFfi {
        val p = CompanionClient(baseUrl).getProject(token, projectId)
        mapOf(
          "id" to p.id,
          "name" to p.name,
          "slug" to p.slug,
          "updated_at" to p.updatedAt,
          "designs" to p.designs.map { d ->
            mapOf(
              "id" to d.id,
              "name" to d.name,
              "sheet_count" to d.sheetCount,
              "bom_lines" to d.bomLines.map { line ->
                mapOf(
                  "ref" to line.ref,
                  "value" to line.value,
                  "footprint" to line.footprint,
                  "qty" to line.qty,
                )
              },
            )
          },
          "artifact_manifest" to p.artifactManifest.map { a ->
            mapOf(
              "name" to a.name,
              "content_type" to a.contentType,
              "sha256" to a.sha256,
              "bytes" to a.bytes,
            )
          },
        )
      }
    }

    AsyncFunction("listProposals") { baseUrl: String, token: String, projectId: String, status: String? ->
      runFfi {
        CompanionClient(baseUrl).listProposals(token, projectId, status).map { p ->
          mapOf(
            "id" to p.id,
            "kind" to p.kind,
            "title" to p.title,
            "summary" to p.summary,
            "created_at" to p.createdAt,
            "payload_json" to p.payloadJson,
            "status" to p.status,
            "decision" to p.decision,
            "note" to p.note,
          )
        }
      }
    }

    AsyncFunction("decideProposal") { baseUrl: String, token: String, projectId: String, proposalId: String, decision: String, note: String? ->
      runFfi {
        val p = CompanionClient(baseUrl).decideProposal(token, projectId, proposalId, decision, note)
        mapOf(
          "id" to p.id,
          "kind" to p.kind,
          "title" to p.title,
          "summary" to p.summary,
          "created_at" to p.createdAt,
          "payload_json" to p.payloadJson,
          "status" to p.status,
          "decision" to p.decision,
          "note" to p.note,
        )
      }
    }

    AsyncFunction("getArtifact") { baseUrl: String, token: String, projectId: String, name: String ->
      runFfi {
        val art = CompanionClient(baseUrl).getArtifact(token, projectId, name)
        mapOf(
          "contentType" to art.contentType,
          // Always base64 — JS decodes text when appropriate (SVG). Never mojibake binary.
          "bodyBase64" to Base64.encodeToString(art.body, Base64.NO_WRAP),
        )
      }
    }

    AsyncFunction("startSync") { baseUrl: String, token: String, projectId: String? ->
      runFfi {
        val context = requireNotNull(appContext.reactContext) { "React context unavailable" }
        val installationId = Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ANDROID_ID,
        ).takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        val statePath = context.filesDir.resolve("tokito/realtime-sync.sqlite3")
        statePath.parentFile?.mkdirs()
        val subscriptionId = projectId ?: "workspace"
        subscriptions.remove(subscriptionId)?.stop()
        val listener = object : SyncListener {
          override fun onStatus(status: String) {
            sendEvent("onSyncStatus", mapOf("subscriptionId" to subscriptionId, "status" to status))
          }

          override fun onBootstrap(cursor: ULong, resourcesJson: String) {
            sendEvent(
              "onSyncBootstrap",
              mapOf(
                "subscriptionId" to subscriptionId,
                "cursor" to cursor.toString(),
                "resourcesJson" to resourcesJson,
              ),
            )
          }

          override fun onEvent(eventJson: String) {
            sendEvent("onSyncEvent", mapOf("subscriptionId" to subscriptionId, "eventJson" to eventJson))
          }

          override fun onConflict(
            commandJson: String,
            currentVersion: ULong,
            currentPayloadJson: String,
          ) {
            sendEvent(
              "onSyncConflict",
              mapOf(
                "subscriptionId" to subscriptionId,
                "commandJson" to commandJson,
                "currentVersion" to currentVersion.toString(),
                "currentPayloadJson" to currentPayloadJson,
              ),
            )
          }

          override fun onError(message: String) {
            sendEvent("onSyncError", mapOf("subscriptionId" to subscriptionId, "message" to message))
          }
        }
        val subscription = CompanionClient(baseUrl).startSync(
          token,
          installationId,
          Build.MODEL ?: "Android device",
          "android",
          projectId,
          statePath.absolutePath,
          listener,
        )
        subscriptions[subscriptionId] = subscription
        subscriptionId
      }
    }

    AsyncFunction("stopSync") { subscriptionId: String ->
      subscriptions.remove(subscriptionId)?.stop()
    }

    AsyncFunction("enqueueSyncCommand") {
      subscriptionId: String,
      projectId: String,
      designId: String?,
      resource: String,
      payloadJson: String,
      ->
      runFfi {
        val subscription = subscriptions[subscriptionId]
          ?: throw IllegalStateException("sync subscription is not running")
        subscription.enqueueCommand(projectId, designId, resource, payloadJson)
      }
    }

    AsyncFunction("resolveSyncConflict") {
      subscriptionId: String,
      commandId: String,
      keepLocal: Boolean,
      ->
      runFfi {
        val subscription = subscriptions[subscriptionId]
          ?: throw IllegalStateException("sync subscription is not running")
        subscription.resolveConflict(commandId, keepLocal)
      }
    }
  }

  private fun <T> runFfi(block: () -> T): T {
    return try {
      block()
    } catch (err: CompanionException) {
      // Stable codes for TS (class simple name: Cooldown, Unauthorized, Network, …)
      val code = err::class.java.simpleName
      throw CodedException(code, err.message ?: code, err)
    } catch (err: Exception) {
      throw CodedException("Unexpected", err.message ?: "unexpected native error", err)
    }
  }
}
