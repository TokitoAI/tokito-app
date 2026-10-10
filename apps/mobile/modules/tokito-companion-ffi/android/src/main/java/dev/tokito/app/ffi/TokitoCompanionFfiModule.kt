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
import uniffi.tokito_companion_ffi.ReviewSession
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
  private val reviews = ConcurrentHashMap<String, ReviewSession>()
  private val runs = ConcurrentHashMap<String, Pair<String, RunSubscription>>()
  private val queueLock = Any()
  private val startupLock = Any()
  private val teardown = java.util.concurrent.Executors.newSingleThreadExecutor()
  private val authorization = AuthorizationEpoch()
  private var queueScope: Pair<String, String>? = null
  private var queueOwner: String? = null
  private var queueChat: ConversationSession? = null
  private var queueReviews: ReviewSession? = null
  private var queueTimer: java.util.concurrent.ScheduledExecutorService? = null

  private fun stopQueues() = synchronized(queueLock) {
    authorization.invalidate()
    val retiredSubscriptions = subscriptions.values.toList(); subscriptions.clear()
    val retiredTimer = queueTimer; queueTimer = null
    val retiredChat = queueChat; queueChat = null
    val retiredReviews = queueReviews; queueReviews = null
    conversations.clear(); reviews.clear()
    val retiredRuns = runs.values.map { it.second }; runs.clear()
    queueScope = null; queueOwner = null
    // Activation is synchronous on the JS control plane. Retire ownership now;
    // native shutdown and any future blocking disposal stay on a worker thread.
    teardown.execute {
      retiredTimer?.shutdownNow()
      retiredSubscriptions.forEach { it.stop() }
      retiredRuns.forEach { it.stop() }
      retiredChat?.shutdown()
      retiredReviews?.shutdown()
    }
  }
  private fun revoked(lease: AuthorizationEpoch.Lease?) = synchronized(queueLock) {
    if (!authorization.invalidateIfOwned(lease)) return@synchronized
    val owner = queueOwner
    stopQueues()
    if (owner != null) sendEvent("onAuthorizationRevoked", mapOf("owner" to owner))
  }
  private fun ensureQueues(base: String, token: String, owner: String): AuthorizationEpoch.Lease = synchronized(startupLock) {
    val lease = synchronized(queueLock) { authorization.requireOwner(base, token, owner) }
    if (synchronized(queueLock) { queueScope == (base to token) }) return@synchronized lease
    val context = requireNotNull(appContext.reactContext)
    val preferences = context.getSharedPreferences("tokito-installation", 0)
    val device = preferences.getString("id", null) ?: UUID.randomUUID().toString().also { check(preferences.edit().putString("id", it).commit()) }
    val listener = object : ConversationListener {
      override fun onChange(payloadJson: String) = synchronized(queueLock) {
        if (!authorization.owns(lease)) return@synchronized
        if (JSONObject(payloadJson).optString("type") == "unauthorized") { revoked(lease); return@synchronized }
        conversations.keys.forEach { id -> sendEvent("onConversationChange", mapOf("sessionId" to id, "watchId" to "", "payloadJson" to payloadJson)) }
      }
    }
    val chat = CompanionClient(base).openConversations(token, device, context.filesDir.resolve("tokito/conversations").absolutePath, listener)
    val review = try { CompanionClient(base).openReviews(token, device, context.filesDir.resolve("tokito/reviews").absolutePath) } catch (error: Exception) { chat.shutdown(); throw error }
    synchronized(queueLock) {
      if (!authorization.owns(lease)) {
        chat.shutdown(); review.shutdown()
        throw CodedException("Unauthorized", "Session startup was superseded", null)
      }
      queueChat = chat; queueReviews = review; queueScope = base to token
      queueTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor().also { executor ->
        executor.scheduleWithFixedDelay({
          try { review.replayPending() }
          catch (error: CompanionException) { if (error is CompanionException.Unauthorized) revoked(lease) }
          catch (_: Exception) { /* Durable commands remain pending; next tick retries. */ }
        }, 0, 3, java.util.concurrent.TimeUnit.SECONDS)
      }
    }
    lease
  }

  override fun definition() = ModuleDefinition {
    Name("TokitoCompanionFfi")
    Events("onSyncStatus", "onSyncBootstrap", "onSyncEvent", "onSyncConflict", "onSyncError", "onConversationChange", "onAuthorizationRevoked")
    AsyncFunction("httpDiagnostics") { uniffi.tokito_companion_ffi.drainHttpDiagnostics() }

    OnDestroy {
      stopQueues()
      teardown.shutdown()
    }

    // Synchronous control-plane activation precedes every asynchronous startup.
    Function("activateWorkspace") { base: String, token: String, owner: String -> synchronized(queueLock) {
      stopQueues()
      authorization.begin(base, token, owner)
      queueOwner = owner
    }; Unit }
    Function("deactivateWorkspace") { owner: String -> synchronized(queueLock) { if (queueOwner == owner) stopQueues() }; Unit }
    AsyncFunction("startQueues") { base: String, token: String, owner: String ->
      val lease = authorization.requireOwner(base, token, owner)
      runFfi(lease) { ensureQueues(base, token, owner); Unit }
    }
    AsyncFunction("stopQueues") { owner: String -> synchronized(queueLock) { if (queueOwner == owner) stopQueues() } }

    AsyncFunction("baseUrl") { baseUrl: String ->
      CompanionClient(baseUrl).baseUrl()
    }

    AsyncFunction("getSchematicPreview") { base: String, token: String, project: String, design: String, version: String, sheet: String ->
      runFfi(authorization.matching(base, token)) { uniffi.tokito_companion_ffi.getSchematicPreview(base, token, project, design, version.toULong(), sheet, requireNotNull(appContext.reactContext).filesDir.resolve("tokito/preview-state").absolutePath) }
    }
    AsyncFunction("cachedSchematicPreview") { base: String, token: String, project: String, design: String, version: String, sheet: String ->
      runFfi(authorization.matching(base, token)) { uniffi.tokito_companion_ffi.cachedSchematicPreview(base, token, project, design, version.toULong(), sheet, requireNotNull(appContext.reactContext).filesDir.resolve("tokito/preview-state").absolutePath) }
    }

    AsyncFunction("openReviews") { baseUrl: String, token: String, sessionId: String, owner: String ->
      val lease = authorization.requireOwner(baseUrl, token, owner)
      runFfi(lease) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val context = requireNotNull(appContext.reactContext)
        val preferences = context.getSharedPreferences("tokito-installation", 0)
        val device = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
          check(preferences.edit().putString("id", it).commit())
        }
        ensureQueues(baseUrl, token, owner)
        synchronized(queueLock) {
          check(authorization.owns(lease)) { "Workspace startup was superseded" }
          reviews[sessionId] = requireNotNull(queueReviews)
        }
        sessionId
      }
    }
    AsyncFunction("closeReviews") { sessionId: String -> reviews.remove(sessionId); Unit }
    AsyncFunction("loadReviews") { sessionId: String, project: String, design: String -> runFfi { review(sessionId).load(project, design) } }
    AsyncFunction("localReviewCommands") { sessionId: String, project: String, design: String -> runFfi { review(sessionId).localCommands(project, design) } }
    AsyncFunction("decideReview") { sessionId: String, project: String, design: String, run: String, seq: String, action: String, selection: String ->
      runFfi { review(sessionId).decide(project, design, run, seq.toULong(), action, selection) }
    }

    AsyncFunction("openConversations") { baseUrl: String, token: String, sessionId: String, owner: String ->
      val lease = authorization.requireOwner(baseUrl, token, owner)
      runFfi(lease) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val context = requireNotNull(appContext.reactContext)
        val preferences = context.getSharedPreferences("tokito-installation", 0)
        val device = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
          check(preferences.edit().putString("id", it).commit())
        }
        ensureQueues(baseUrl, token, owner)
        synchronized(queueLock) {
          check(authorization.owns(lease)) { "Workspace startup was superseded" }
          conversations[sessionId] = requireNotNull(queueChat)
        }
        sessionId
      }
    }

    AsyncFunction("closeConversations") { sessionId: String ->
      runs.entries.filter { it.value.first == sessionId }.forEach { (id, pair) ->
        if (runs.remove(id, pair)) pair.second.stop()
      }
      conversations.remove(sessionId)
      Unit
    }
    AsyncFunction("conversationThreads") { sessionId: String, project: String, design: String ->
      runFfi { conversation(sessionId).listThreads(project, design) }
    }
    Function("newConversationCommand") { UUID.randomUUID().toString() }
    AsyncFunction("startConversation") { sessionId: String, command: String, project: String, design: String, content: String, version: String, kind: String, activeSheetId: String? ->
      runFfi(operation = "chat_start") {
        val title = content.substring(0, content.offsetByCodePoints(0, minOf(60, content.codePointCount(0, content.length))))
        conversation(sessionId).startConversation(command, project, design, title, content, version.toULong(), kind, activeSheetId)
      }
    }
    AsyncFunction("conversationHistory") { sessionId: String, project: String, design: String, thread: String, before: String? ->
      runFfi(operation = "chat_history") { conversation(sessionId).history(project, design, thread, before?.toULong()) }
    }
    AsyncFunction("cachedConversationHistory") { sessionId: String, thread: String, after: String ->
      runFfi { conversation(sessionId).cachedHistory(thread, after.toULong()) }
    }
    AsyncFunction("sendConversationMessage") { sessionId: String, project: String, design: String, thread: String, content: String, version: String, kind: String, activeSheetId: String? ->
      runFfi(operation = "chat_send") { conversation(sessionId).send(project, design, thread, content, version.toULong(), kind, activeSheetId) }
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
    AsyncFunction("blockedConversationMessages") { sessionId: String, thread: String ->
      runFfi { conversation(sessionId).blockedMessages(thread) }
    }
    AsyncFunction("queuedConversationMessage") { sessionId: String, thread: String ->
      runFfi { conversation(sessionId).queuedMessage(thread) }
    }
    AsyncFunction("recoveredConversationDraft") { sessionId: String, thread: String ->
      runFfi { conversation(sessionId).recoveredDraft(thread) }
    }
    AsyncFunction("restoreBlockedMessage") { sessionId: String, messageId: String ->
      runFfi { conversation(sessionId).restoreBlockedMessage(messageId) }
    }
    AsyncFunction("watchConversationRun") { sessionId: String, run: String, after: String, watchId: String ->
      runFfi(operation = "chat_watch") {
        val lease = authorization.capture()
        require(watchId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        runs.remove(watchId)?.second?.stop()
        val listener = object : ConversationListener {
          override fun onChange(payloadJson: String) {
            if (!authorization.owns(lease)) return
            if (JSONObject(payloadJson).optString("type") == "unauthorized") { revoked(lease); return }
            sendEvent("onConversationChange", mapOf("sessionId" to sessionId, "watchId" to watchId, "payloadJson" to payloadJson))
          }
        }
        runs[watchId] = sessionId to conversation(sessionId).watchRun(run, after.toULong(), listener)
        watchId
      }
    }
    AsyncFunction("stopConversationRunWatch") { watchId: String ->
      runFfi(operation = "chat_stop_watch") { runs.remove(watchId)?.second?.stop(); Unit }
    }

    AsyncFunction("otpRequest") { baseUrl: String, email: String ->
      runFfi(null) { CompanionClient(baseUrl).otpRequest(email) }
    }

    AsyncFunction("otpVerify") { baseUrl: String, email: String, code: String ->
      runFfi(null) {
        val session = CompanionClient(baseUrl).otpVerify(email, code)
        mapOf(
          "token" to session.token,
          "email" to session.email,
          "plan" to session.plan,
        )
      }
    }

    AsyncFunction("oauthExchange") { baseUrl: String, code: String ->
      runFfi(null) {
        val session = CompanionClient(baseUrl).oauthExchange(code)
        mapOf(
          "token" to session.token,
          "email" to session.email,
          "plan" to session.plan,
        )
      }
    }

    AsyncFunction("me") { baseUrl: String, token: String ->
      runFfi(authorization.matching(baseUrl, token)) {
        val account = CompanionClient(baseUrl).me(token)
        mapOf(
          "email" to account.email,
          "plan" to account.plan,
        )
      }
    }

    AsyncFunction("resolveIdentity") { baseUrl: String, token: String ->
      runFfi(authorization.matching(baseUrl, token)) {
        val context = requireNotNull(appContext.reactContext)
        val identity = CompanionClient(baseUrl).resolveIdentity(token, context.filesDir.resolve("tokito/verified-identity.sqlite3").absolutePath)
        JSONObject(mapOf("account" to mapOf("email" to identity.account.email, "plan" to identity.account.plan), "online" to identity.online)).toString()
      }
    }

    AsyncFunction("forgetIdentity") { baseUrl: String, token: String ->
      runFfi(null) {
        val context = requireNotNull(appContext.reactContext)
        CompanionClient(baseUrl).forgetIdentity(token, context.filesDir.resolve("tokito/verified-identity.sqlite3").absolutePath)
      }
    }

    AsyncFunction("listProjects") { baseUrl: String, token: String ->
      runFfi(authorization.matching(baseUrl, token)) {
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
      runFfi(authorization.matching(baseUrl, token)) {
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
      runFfi(authorization.matching(baseUrl, token)) {
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
      runFfi(authorization.matching(baseUrl, token)) {
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
      runFfi(authorization.matching(baseUrl, token)) {
        val art = CompanionClient(baseUrl).getArtifact(token, projectId, name)
        mapOf(
          "contentType" to art.contentType,
          // Always base64 — JS decodes text when appropriate (SVG). Never mojibake binary.
          "bodyBase64" to Base64.encodeToString(art.body, Base64.NO_WRAP),
        )
      }
    }

    AsyncFunction("startSync") { baseUrl: String, token: String, projectId: String?, owner: String ->
      val lease = authorization.requireOwner(baseUrl, token, owner)
      runFfi(lease) {
        ensureQueues(baseUrl, token, owner)
        val context = requireNotNull(appContext.reactContext) { "React context unavailable" }
        val installationId = Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ANDROID_ID,
        ).takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        val statePath = context.filesDir.resolve("tokito/realtime-sync.sqlite3")
        statePath.parentFile?.mkdirs()
        val subscriptionId = UUID.randomUUID().toString()
        subscriptions.remove(subscriptionId)?.stop()
        val listener = object : SyncListener {
          override fun onStatus(status: String) {
            if (!authorization.owns(lease)) return
            sendEvent("onSyncStatus", mapOf("subscriptionId" to subscriptionId, "status" to status))
          }

          override fun onBootstrap(cursor: ULong, resourcesJson: String) {
            if (!authorization.owns(lease)) return
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
            if (!authorization.owns(lease)) return
            sendEvent("onSyncEvent", mapOf("subscriptionId" to subscriptionId, "eventJson" to eventJson))
          }

          override fun onConflict(
            commandJson: String,
            currentVersion: ULong,
            currentPayloadJson: String,
          ) {
            if (!authorization.owns(lease)) return
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
            if (!authorization.owns(lease)) return
            val diagnostics = uniffi.tokito_companion_ffi.drainHttpDiagnostics()
            if (diagnostics != "[]") Log.w("TokitoHttp", diagnostics)
            if (message.contains("unauthorized", ignoreCase = true) || message.contains("session expired", ignoreCase = true)) { revoked(lease); return }
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
        synchronized(queueLock) {
          if (!authorization.owns(lease)) {
            subscription.stop()
            throw CodedException("Unauthorized", "Session startup was superseded", null)
          }
          subscriptions[subscriptionId] = subscription
        }
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

  private fun <T> runFfi(lease: AuthorizationEpoch.Lease? = authorization.capture(), operation: String = "native_call", block: () -> T): T {
    return try {
      block().also {
        if (operation != "native_call") Log.i("TokitoChatBoundary", "$operation completed")
      }
    } catch (err: CompanionException) {
      // Stable codes for TS (class simple name: Cooldown, Unauthorized, Network, …)
      val code = err::class.java.simpleName
      val diagnostics = uniffi.tokito_companion_ffi.drainHttpDiagnostics()
      if (diagnostics != "[]") Log.w("TokitoHttp", diagnostics)
      if (err is CompanionException.Unauthorized) revoked(lease)
      Log.w("TokitoChatBoundary", "$operation rejected: $code")
      Log.w(TAG, "UniFFI call failed: $code")
      throw CodedException(code, err.message ?: code, err)
    } catch (err: Exception) {
      val diagnostics = uniffi.tokito_companion_ffi.drainHttpDiagnostics()
      if (diagnostics != "[]") Log.w("TokitoHttp", diagnostics)
      // Exception text can contain caller data; only fixed operation names and
      // exception classes belong in diagnostics.
      Log.e("TokitoChatBoundary", "$operation bridge_rejected: ${err::class.java.simpleName}")
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

  private fun review(id: String): ReviewSession = reviews[id]
    ?: throw CodedException("Unauthorized", "Open a signed-in review session first", null)

  private companion object {
    const val TAG = "TokitoCompanionFfi"
  }
}
