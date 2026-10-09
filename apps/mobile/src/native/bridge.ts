/**
 * Production cloud client: UniFFI Rust via Expo native module on device.
 * HttpCloudClient is imported only for Node/CI unit tests (never on device).
 */

import { Platform } from "react-native";
import {
  getTokitoCompanionFfi,
  isTokitoCompanionFfiLinked,
  type NativeEventSubscription,
  type SyncNativeEventMap,
} from "../../modules/tokito-companion-ffi/src";
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
  startSync?(token: string, projectId?: string): Promise<string>;
  stopSync?(subscriptionId: string): Promise<void>;
  enqueueSyncCommand?(
    subscriptionId: string,
    projectId: string,
    designId: string | null,
    resource: SyncResourceName,
    payload: unknown,
  ): Promise<string>;
  resolveSyncConflict?(
    subscriptionId: string,
    commandId: string,
    keepLocal: boolean,
  ): Promise<void>;
  addSyncListener?<K extends keyof SyncNativeEventMap>(
    eventName: K,
    listener: (event: SyncNativeEventMap[K]) => void,
  ): NativeEventSubscription;
};

export type SyncResourceName =
  | "project"
  | "design"
  | "schematic"
  | "bom"
  | "chat"
  | "conversations"
  | "suggestions"
  | "physical_sessions";

/** Map Expo CodedException codes from Kotlin CompanionException simple names. */
function mapNativeError(err: unknown): CompanionError {
  if (err instanceof CompanionError) return err;
  const coded = err as { code?: string; message?: string };
  const code = (coded.code ?? "").replace(/Exception$/i, "");
  const message = coded.message ?? (err instanceof Error ? err.message : String(err));
  switch (code) {
    case "Cooldown":
      return CompanionError.cooldown();
    case "RateLimited":
      return CompanionError.rateLimited();
    case "SendFailed":
      return CompanionError.sendFailed();
    case "InvalidCode":
      return CompanionError.invalidCode();
    case "Unauthorized":
      return CompanionError.unauthorized();
    case "Unavailable":
      return CompanionError.unavailable(message);
    case "Network":
      return CompanionError.network(message);
    case "NativeCompatibility":
      return CompanionError.nativeCompatibility();
    case "Unexpected":
      return CompanionError.unexpected();
    default:
      return CompanionError.unexpected();
  }
}

function requireString(v: unknown, field: string): string {
  if (typeof v !== "string") {
    throw CompanionError.unexpected();
  }
  void field;
  return v;
}

function requireNumber(v: unknown): number {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string" && v.trim() !== "" && Number.isFinite(Number(v))) {
    return Number(v);
  }
  throw CompanionError.unexpected();
}

function parseProposal(raw: Record<string, unknown>): Proposal {
  let payload: unknown = {};
  const payloadJson = raw.payload_json;
  if (typeof payloadJson === "string" && payloadJson.length > 0) {
    try {
      payload = JSON.parse(payloadJson) as unknown;
    } catch {
      throw CompanionError.unexpected();
    }
  }
  return {
    id: requireString(raw.id, "id"),
    kind: requireString(raw.kind, "kind"),
    title: requireString(raw.title, "title"),
    summary: requireString(raw.summary, "summary"),
    created_at: requireString(raw.created_at, "created_at"),
    payload,
    status: requireString(raw.status, "status"),
    decision: typeof raw.decision === "string" ? raw.decision : null,
    note: typeof raw.note === "string" ? raw.note : null,
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
        ref: requireString(line.ref, "ref"),
        value: requireString(line.value, "value"),
        footprint: typeof line.footprint === "string" ? line.footprint : "",
        qty: requireNumber(line.qty),
      };
    });
    return {
      id: requireString(row.id, "id"),
      name: requireString(row.name, "name"),
      sheet_count: requireNumber(row.sheet_count),
      bom_lines,
    };
  });
  const artifactsRaw = Array.isArray(raw.artifact_manifest) ? raw.artifact_manifest : [];
  const artifact_manifest: ArtifactMeta[] = artifactsRaw.map((a) => {
    const row = a as Record<string, unknown>;
    return {
      name: requireString(row.name, "name"),
      content_type: requireString(row.content_type, "content_type"),
      sha256: requireString(row.sha256, "sha256"),
      bytes: requireNumber(row.bytes),
    };
  });
  return {
    id: requireString(raw.id, "id"),
    name: requireString(raw.name, "name"),
    slug: requireString(raw.slug, "slug"),
    updated_at: requireString(raw.updated_at, "updated_at"),
    designs,
    artifact_manifest,
  };
}

function decodeBase64Utf8(b64: string): string {
  // RN / Hermes: atob available; prefer Buffer when present (Node tests).
  const g = globalThis as { Buffer?: { from: (s: string, enc: string) => { toString: (e: string) => string } }; atob?: (s: string) => string };
  if (typeof g.Buffer !== "undefined") {
    return g.Buffer.from(b64, "base64").toString("utf8");
  }
  if (typeof g.atob === "function") {
    const binary = g.atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return new TextDecoder("utf-8").decode(bytes);
  }
  throw CompanionError.unexpected();
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

  async resolveIdentity(token: string): Promise<{ account: Account; online: boolean }> {
    try {
      const result = JSON.parse(await this.native.resolveIdentity(this.base, token));
      if (typeof result?.online !== "boolean") throw CompanionError.unexpected();
      return { account: { email: requireString(result?.account?.email, "email"), plan: requireString(result?.account?.plan, "plan") }, online: result.online };
    }
    catch (err) { throw mapNativeError(err); }
  }

  async forgetIdentity(token: string): Promise<void> {
    try { await this.native.forgetIdentity(this.base, token); }
    catch (err) { throw mapNativeError(err); }
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
      const encoded = await this.native.getProject(this.base, token, projectId);
      const decoded = JSON.parse(encoded) as unknown;
      if (decoded === null || typeof decoded !== "object" || Array.isArray(decoded)) {
        throw CompanionError.unexpected();
      }
      const raw = decoded as Record<string, unknown>;
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
      const body = decodeBase64Utf8(art.bodyBase64);
      return { contentType: art.contentType, body };
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async startSync(token: string, projectId?: string): Promise<string> {
    try {
      return await this.native.startSync(this.base, token, projectId ?? null);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async stopSync(subscriptionId: string): Promise<void> {
    try {
      await this.native.stopSync(subscriptionId);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async enqueueSyncCommand(
    subscriptionId: string,
    projectId: string,
    designId: string | null,
    resource: SyncResourceName,
    payload: unknown,
  ): Promise<string> {
    try {
      return await this.native.enqueueSyncCommand(
        subscriptionId,
        projectId,
        designId,
        resource,
        JSON.stringify(payload),
      );
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  async resolveSyncConflict(
    subscriptionId: string,
    commandId: string,
    keepLocal: boolean,
  ): Promise<void> {
    try {
      await this.native.resolveSyncConflict(subscriptionId, commandId, keepLocal);
    } catch (err) {
      throw mapNativeError(err);
    }
  }

  addSyncListener<K extends keyof SyncNativeEventMap>(
    eventName: K,
    listener: (event: SyncNativeEventMap[K]) => void,
  ): NativeEventSubscription {
    return this.native.addListener(eventName, listener);
  }
}

/**
 * Device builds require UniFFI. Node/CI uses HttpCloudClient for unit tests only.
 */
export function createCloudClient(baseUrl: string): CloudClient {
  const normalized = normalizeBaseUrl(baseUrl);
  if (Platform.OS === "android" || Platform.OS === "ios") {
    if (!isTokitoCompanionFfiLinked()) {
      throw new Error(
        "TokitoCompanionFfi native module is not linked — rebuild with scripts/build-android-ffi.sh",
      );
    }
    return new NativeCompanionClient(normalized);
  }
  return new HttpCloudClient(normalized);
}

export { isTokitoCompanionFfiLinked };
