/**
 * Production cloud client: UniFFI Rust via Expo native module on device.
 * HttpCloudClient remains for Node CI tests only.
 */

import { Platform } from "react-native";
import { getTokitoCompanionFfi, isTokitoCompanionFfiLinked } from "../../modules/tokito-companion-ffi/src";
import { HttpCloudClient } from "../api/cloudHttp";
import type {
  ArtifactMeta,
  BomLine,
  DesignSummary,
  ProjectDetail,
  ProjectListItem,
  Proposal,
} from "../api/cloudHttp";
import { normalizeBaseUrl } from "../cloud";
import type { Account, CompanionClientPort, Session } from "./types";
import { CompanionError } from "./types";

export type CloudClient = CompanionClientPort & {
  listProjects(token: string): Promise<ProjectListItem[]>;
  getProject(token: string, projectId: string): Promise<ProjectDetail>;
  listProposals(token: string, projectId: string, status?: string): Promise<Proposal[]>;
  decideProposal(
    token: string,
    projectId: string,
    proposalId: string,
    decision: "approve" | "reject",
    note?: string,
  ): Promise<Proposal>;
  getArtifactText(
    token: string,
    projectId: string,
    name: string,
  ): Promise<{ contentType: string; body: string }>;
};

function mapNativeError(err: unknown): CompanionError {
  if (err instanceof CompanionError) return err;
  const coded = err as { code?: string; message?: string };
  const code = (coded.code ?? "").toLowerCase();
  const message = coded.message ?? (err instanceof Error ? err.message : String(err));
  const lower = `${code} ${message}`.toLowerCase();
  if (lower.includes("cooldown")) return CompanionError.cooldown();
  if (lower.includes("ratelimited") || lower.includes("rate_limited") || lower.includes("rate limited")) {
    return CompanionError.rateLimited();
  }
  if (lower.includes("sendfailed") || lower.includes("send_failed")) return CompanionError.sendFailed();
  if (lower.includes("invalidcode") || lower.includes("invalid")) return CompanionError.invalidCode();
  if (lower.includes("unauthorized") || lower.includes("expired")) return CompanionError.unauthorized();
  if (lower.includes("unavailable")) return CompanionError.unavailable();
  if (lower.includes("network")) return CompanionError.network(message);
  return CompanionError.unexpected();
}

function parseProposal(raw: Record<string, unknown>): Proposal {
  let payload: unknown = {};
  const payloadJson = raw.payload_json;
  if (typeof payloadJson === "string" && payloadJson.length > 0) {
    try {
      payload = JSON.parse(payloadJson) as unknown;
    } catch {
      payload = payloadJson;
    }
  } else if (raw.payload !== undefined) {
    payload = raw.payload;
  }
  return {
    id: String(raw.id ?? ""),
    kind: String(raw.kind ?? ""),
    title: String(raw.title ?? ""),
    summary: String(raw.summary ?? ""),
    created_at: String(raw.created_at ?? ""),
    payload,
    status: String(raw.status ?? ""),
    decision: (raw.decision as string | null | undefined) ?? null,
    note: (raw.note as string | null | undefined) ?? null,
  };
}

function parseProjectDetail(raw: Record<string, unknown>): ProjectDetail {
  const designsRaw = Array.isArray(raw.designs) ? raw.designs : [];
  const designs: DesignSummary[] = designsRaw.map((d) => {
    const row = d as Record<string, unknown>;
    const bomRaw = Array.isArray(row.bom_lines) ? row.bom_lines : [];
    const bom_lines: BomLine[] = bomRaw.map((b) => {
      const line = b as Record<string, unknown>;
      return {
        ref: String(line.ref ?? ""),
        value: String(line.value ?? ""),
        footprint: String(line.footprint ?? ""),
        qty: Number(line.qty ?? 0),
      };
    });
    return {
      id: String(row.id ?? ""),
      name: String(row.name ?? ""),
      sheet_count: Number(row.sheet_count ?? 0),
      bom_lines,
    };
  });
  const artifactsRaw = Array.isArray(raw.artifact_manifest) ? raw.artifact_manifest : [];
  const artifact_manifest: ArtifactMeta[] = artifactsRaw.map((a) => {
    const row = a as Record<string, unknown>;
    return {
      name: String(row.name ?? ""),
      content_type: String(row.content_type ?? ""),
      sha256: String(row.sha256 ?? ""),
      bytes: Number(row.bytes ?? 0),
    };
  });
  return {
    id: String(raw.id ?? ""),
    name: String(raw.name ?? ""),
    slug: String(raw.slug ?? ""),
    updated_at: String(raw.updated_at ?? ""),
    designs,
    artifact_manifest,
  };
}

/**
 * UniFFI-backed client. Requires `TokitoCompanionFfi` Expo module + jniLibs in the APK.
 */
export class NativeCompanionClient implements CloudClient {
  private readonly base: string;
  private readonly native = getTokitoCompanionFfi();

  constructor(baseUrl: string) {
    this.base = normalizeBaseUrl(baseUrl);
  }

  baseUrl(): string {
    return this.base;
  }

  async otpRequest(email: string): Promise<void> {
    try {
      await this.native.otpRequest(this.base, email);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async otpVerify(email: string, code: string): Promise<Session> {
    try {
      return await this.native.otpVerify(this.base, email, code);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async oauthExchange(code: string): Promise<Session> {
    try {
      return await this.native.oauthExchange(this.base, code);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async me(token: string): Promise<Account> {
    try {
      return await this.native.me(this.base, token);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async listProjects(token: string): Promise<ProjectListItem[]> {
    try {
      return await this.native.listProjects(this.base, token);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async getProject(token: string, projectId: string): Promise<ProjectDetail> {
    try {
      const raw = (await this.native.getProject(this.base, token, projectId)) as Record<
        string,
        unknown
      >;
      return parseProjectDetail(raw);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async listProposals(
    token: string,
    projectId: string,
    status?: string,
  ): Promise<Proposal[]> {
    try {
      const rows = await this.native.listProposals(
        this.base,
        token,
        projectId,
        status ?? null,
      );
      return rows.map(parseProposal);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async decideProposal(
    token: string,
    projectId: string,
    proposalId: string,
    decision: "approve" | "reject",
    note?: string,
  ): Promise<Proposal> {
    try {
      const raw = await this.native.decideProposal(
        this.base,
        token,
        projectId,
        proposalId,
        decision,
        note ?? null,
      );
      return parseProposal(raw);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async getArtifactText(
    token: string,
    projectId: string,
    name: string,
  ): Promise<{ contentType: string; body: string }> {
    try {
      const art = await this.native.getArtifact(this.base, token, projectId, name);
      return { contentType: art.contentType, body: art.body };
    } catch (err) {
      throw mapNativeError(err);
    }
  }
}

/**
 * Prefer UniFFI when the Expo module is linked (production APK).
 * Hard-fail on iOS/Android if the module is missing — never silently use JS HTTP
 * on device. Node/CI without the native binary uses HttpCloudClient.
 */
export function createCloudClient(baseUrl: string): CloudClient {
  const normalized = normalizeBaseUrl(baseUrl);
  if (isTokitoCompanionFfiLinked()) {
    return new NativeCompanionClient(normalized);
  }
  if (Platform.OS === "android" || Platform.OS === "ios") {
    throw new Error(
      "TokitoCompanionFfi native module is not linked — rebuild the APK with scripts/build-android-ffi.sh",
    );
  }
  return new HttpCloudClient(normalized);
}

export { isTokitoCompanionFfiLinked };
