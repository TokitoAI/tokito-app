package dev.tokito.app.ffi

import android.util.Base64
import android.os.Build
import android.provider.Settings
import android.util.Log
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import uniffi.tokito_companion_ffi.CompanionClient
import uniffi.tokito_companion_ffi.CompanionException
import uniffi.tokito_companion_ffi.SyncListener
import uniffi.tokito_companion_ffi.SyncSubscription
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import dev.tokito.physical.symbols.BoardSymbolService
import dev.tokito.physical.symbols.BoardSymbolServices
import uniffi.tokito_companion_ffi.BoardSymbolRequest

/**
 * Expo module bridging UniFFI [CompanionClient] into JS.
 * All calls hit Tokito Cloud through Rust core — no stubs.
 *
 * Generated bindings: `uniffi.tokito_companion_ffi` (Mozilla UniFFI Kotlin).
 */
class TokitoCompanionFfiModule : Module() {
  private val subscriptions = ConcurrentHashMap<String, SyncSubscription>()
  private val symbolExecutor = Executors.newSingleThreadExecutor()
  private val symbolRequests = ConcurrentHashMap.newKeySet<BoardSymbolRequest>()

  override fun definition() = ModuleDefinition {
    Name("TokitoCompanionFfi")
    Events("onSyncStatus", "onSyncBootstrap", "onSyncEvent", "onSyncConflict", "onSyncError")

    OnDestroy {
      subscriptions.values.forEach { it.stop() }
      subscriptions.clear()
      BoardSymbolServices.active = null
      symbolRequests.forEach { runCatching { it.cancel() } }
      // Cancelled queued calls must run their finally block to release UniFFI objects.
      symbolExecutor.shutdown()
    }

    AsyncFunction("configureBoardSymbolService") { baseUrl: String?, token: String? ->
      if (baseUrl == null || token == null) {
        BoardSymbolServices.active = null
        symbolRequests.forEach { runCatching { it.cancel() } }
      } else {
        val endpoint = java.net.URI(baseUrl)
        require(endpoint.scheme == "https" && endpoint.host != null && endpoint.rawUserInfo == null &&
          endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.path in listOf("", "/")) {
          "Symbol service requires an HTTPS origin"
        }
        require(token.length in 32..512) { "A tenant API key is required" }
        BoardSymbolServices.active = BoardSymbolService { revision ->
          suspendCancellableCoroutine { continuation ->
            val request = BoardSymbolRequest(baseUrl, token, revision)
            symbolRequests.add(request)
            continuation.invokeOnCancellation { runCatching { request.cancel() } }
            val operation = Runnable {
              try {
                val result = request.execute()
                if (continuation.isActive) continuation.resume(result)
              } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(
                  IllegalStateException("Symbol generation is unavailable. Check the service and reviewed board context."),
                )
              } finally {
                symbolRequests.remove(request)
                request.close()
              }
            }
            try {
              symbolExecutor.execute(operation)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
              symbolRequests.remove(request)
              request.close()
              if (continuation.isActive) continuation.resumeWithException(
                IllegalStateException("Symbol generation is unavailable."),
              )
            }
          }
        }
      }
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
        // Return one JSON string across Expo's native boundary. Expo's dynamic
        // value converter is not reliable for recursively nested Kotlin maps
        // produced from UniFFI records (and rejects Kotlin Long outright).
        // JSON gives this wire shape one explicit, lossless conversion point.
        JSONObject(mapOf(
          "id" to p.id,
          "name" to p.name,
          "slug" to p.slug,
          "updated_at" to p.updatedAt,
          "designs" to p.designs.map { d ->
            mapOf(
              "id" to d.id,
              "name" to d.name,
              // Expo's JS value converter does not accept Kotlin Long. These
              // server-bounded integer fields remain exact as IEEE-754 values
              // and are validated as finite numbers by the TypeScript boundary.
              "sheet_count" to d.sheetCount.toDouble(),
              "bom_lines" to d.bomLines.map { line ->
                mapOf(
                  "ref" to line.ref,
                  "value" to line.value,
                  "footprint" to line.footprint,
                  "qty" to line.qty.toDouble(),
                )
              },
            )
          },
          "artifact_manifest" to p.artifactManifest.map { a ->
            mapOf(
              "name" to a.name,
              "content_type" to a.contentType,
              "sha256" to a.sha256,
              "bytes" to a.bytes.toDouble(),
            )
          },
        )).toString()
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
      Log.w(TAG, "UniFFI call failed: $code (${err.message})")
      throw CodedException(code, err.message ?: code, err)
    } catch (err: Exception) {
      Log.e(TAG, "Native bridge call failed: ${err::class.java.name}: ${err.message}", err)
      if (err.message?.contains("UniFFI API checksum mismatch") == true) {
        throw CodedException(
          "NativeCompatibility",
          "App components are out of sync. Install the latest Tokito build.",
          err,
        )
      }
      throw CodedException("Unexpected", err.message ?: "unexpected native error", err)
    }
  }

  private companion object {
    const val TAG = "TokitoCompanionFfi"
  }
}
