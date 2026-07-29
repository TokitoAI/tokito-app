package dev.tokito.companion.ffi

import android.util.Base64
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import uniffi.tokito_companion_ffi.CompanionClient
import uniffi.tokito_companion_ffi.CompanionException

/**
 * Expo module bridging UniFFI [CompanionClient] into JS.
 * All calls hit Tokito Cloud through Rust core — no stubs.
 *
 * Generated bindings: `uniffi.tokito_companion_ffi` (Mozilla UniFFI Kotlin).
 */
class TokitoCompanionFfiModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("TokitoCompanionFfi")

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
          "body" to String(art.body, Charsets.UTF_8),
          "bodyBase64" to Base64.encodeToString(art.body, Base64.NO_WRAP),
        )
      }
    }
  }

  private fun <T> runFfi(block: () -> T): T {
    return try {
      block()
    } catch (err: CompanionException) {
      throw CodedException(err::class.java.simpleName, err.message ?: "companion error", err)
    } catch (err: Exception) {
      throw CodedException("Unexpected", err.message ?: "unexpected native error", err)
    }
  }
}
