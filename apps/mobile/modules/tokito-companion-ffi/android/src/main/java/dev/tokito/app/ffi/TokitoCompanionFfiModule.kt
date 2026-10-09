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
import uniffi.tokito_companion_ffi.ConversationListener
import uniffi.tokito_companion_ffi.ConversationSession
import uniffi.tokito_companion_ffi.RunSubscription
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
  private val conversations = ConcurrentHashMap<String, ConversationSession>()
  private val runs = ConcurrentHashMap<String, Pair<String, RunSubscription>>()

  override fun definition() = ModuleDefinition {
    Name("TokitoCompanionFfi")
    Events("onSyncStatus", "onSyncBootstrap", "onSyncEvent", "onSyncConflict", "onSyncError", "onConversationChange")

    OnDestroy {
      subscriptions.values.forEach { it.stop() }
      subscriptions.clear()
      runs.values.forEach { it.second.stop() }
      runs.clear()
      conversations.values.forEach { it.shutdown() }
      conversations.clear()
    }

    AsyncFunction("baseUrl") { baseUrl: String ->
      CompanionClient(baseUrl).baseUrl()
    }

    AsyncFunction("openConversations") { baseUrl: String, token: String, sessionId: String ->
      runFfi {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val context = requireNotNull(appContext.reactContext)
        val preferences = context.getSharedPreferences("tokito-installation", 0)
        val device = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
          check(preferences.edit().putString("id", it).commit())
        }
        conversations.remove(sessionId)?.shutdown()
        val listener = object : ConversationListener {
          override fun onChange(payloadJson: String) {
            sendEvent("onConversationChange", mapOf("sessionId" to sessionId, "watchId" to "", "payloadJson" to payloadJson))
          }
        }
        conversations[sessionId] = CompanionClient(baseUrl).openConversations(token, device, context.filesDir.resolve("tokito/conversations").absolutePath, listener)
        sessionId
      }
    }

    AsyncFunction("closeConversations") { sessionId: String ->
      runs.entries.filter { it.value.first == sessionId }.forEach { (id, pair) ->
        if (runs.remove(id, pair)) pair.second.stop()
      }
      conversations.remove(sessionId)?.shutdown()
    }
    AsyncFunction("conversationThreads") { sessionId: String, project: String, design: String ->
      runFfi { conversation(sessionId).listThreads(project, design) }
    }
    AsyncFunction("createConversation") { sessionId: String, project: String, design: String, title: String ->
      runFfi { conversation(sessionId).createThread(project, design, title) }
    }
    AsyncFunction("conversationHistory") { sessionId: String, project: String, design: String, thread: String, before: String? ->
      runFfi { conversation(sessionId).history(project, design, thread, before?.toULong()) }
    }
    AsyncFunction("cachedConversationHistory") { sessionId: String, thread: String, after: String ->
      runFfi { conversation(sessionId).cachedHistory(thread, after.toULong()) }
    }
    AsyncFunction("sendConversationMessage") { sessionId: String, project: String, design: String, thread: String, content: String, version: String, kind: String ->
      runFfi { conversation(sessionId).send(project, design, thread, content, version.toULong(), kind) }
    }
    AsyncFunction("cancelConversationRun") { sessionId: String, run: String ->
      runFfi { conversation(sessionId).cancel(run) }
    }
    AsyncFunction("deleteConversation") { sessionId: String, project: String, design: String, thread: String ->
      runFfi { conversation(sessionId).deleteThread(project, design, thread) }
    }
    AsyncFunction("discardBlockedMessage") { sessionId: String, messageId: String ->
      runFfi { conversation(sessionId).discardBlockedMessage(messageId) }
    }
    AsyncFunction("watchConversationRun") { sessionId: String, run: String, after: String, watchId: String ->
      runFfi {
        require(watchId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        runs.remove(watchId)?.second?.stop()
        val listener = object : ConversationListener {
          override fun onChange(payloadJson: String) {
            sendEvent("onConversationChange", mapOf("sessionId" to sessionId, "watchId" to watchId, "payloadJson" to payloadJson))
          }
        }
        runs[watchId] = sessionId to conversation(sessionId).watchRun(run, after.toULong(), listener)
        watchId
      }
    }
    AsyncFunction("stopConversationRunWatch") { watchId: String -> runs.remove(watchId)?.second?.stop() }

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

  private fun conversation(id: String): ConversationSession = conversations[id]
    ?: throw CodedException("Unauthorized", "Open a signed-in conversation session first", null)

  private companion object {
    const val TAG = "TokitoCompanionFfi"
  }
}
