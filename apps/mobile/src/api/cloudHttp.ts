/**
 * Real Tokito Cloud HTTP client (fetch) for auth + companion sync.
 * Production RN path until UniFFI is linked into the app binary.
 * Same wire contracts as `tokito-companion-core` — not a stub.
 */

import { normalizeBaseUrl } from "../cloud";
import type {
  Account,
  CompanionClientPort,
  Session,
} from "../native/types";
import { CompanionError } from "../native/types";

export interface BomLine {
  ref: string;
  value: string;
  footprint: string;
  qty: number;
}

export interface DesignSummary {
  id: string;
  name: string;
  sheet_count: number;
  bom_lines: BomLine[];
}

export interface ArtifactMeta {
  name: string;
  content_type: string;
  sha256: string;
  bytes: number;
}

export interface ProjectSnapshot {
  name: string;
  slug: string;
  updated_at: string;
  designs: DesignSummary[];
  artifact_manifest: ArtifactMeta[];
}

export interface ProjectListItem {
  id: string;
  name: string;
  slug: string;
  updated_at: string;
}

export interface ProjectDetail {
  id: string;
  name: string;
  slug: string;
  updated_at: string;
  designs: DesignSummary[];
  artifact_manifest: ArtifactMeta[];
}

export interface Proposal {
  id: string;
  kind: string;
  title: string;
  summary: string;
  created_at: string;
  payload: unknown;
  status: string;
  decision?: string | null;
  note?: string | null;
}

async function readStatus(res: Response): Promise<string | undefined> {
  try {
    const body = (await res.json()) as { status?: string };
    return body.status;
  } catch {
    return undefined;
  }
}

async function mapError(res: Response): Promise<CompanionError> {
  const status = await readStatus(res);
  if (res.status === 401) return CompanionError.unauthorized();
  if (res.status === 503) {
    if (status === "companion_unavailable") {
      return CompanionError.unavailable(
        "Companion sync is not enabled on this server yet",
      );
    }
    return CompanionError.unavailable();
  }
  if (res.status === 404) {
    return CompanionError.unavailable(
      "Companion API not found on this server (deploy tokito-ai with /v1/companion/*)",
    );
  }
  if (res.status === 403) {
    return CompanionError.unauthorized();
  }
  if (res.status === 429) {
    return status === "rate_limited"
      ? CompanionError.rateLimited()
      : CompanionError.cooldown();
  }
  if (res.status === 502) return CompanionError.sendFailed();
  if (status === "invalid_code") return CompanionError.invalidCode();
  return CompanionError.unexpected();
}

const REQUEST_TIMEOUT_MS = 20_000;

async function request(input: string, init?: RequestInit): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } catch (err) {
    if (err instanceof Error && err.name === "AbortError") {
      throw CompanionError.network("Request timed out — check network or Tokito Cloud");
    }
    const msg = err instanceof Error ? err.message : String(err);
    throw CompanionError.network(msg);
  } finally {
    clearTimeout(timer);
  }
}

export class HttpCloudClient implements CompanionClientPort {
  readonly base: string;

  constructor(baseUrl: string) {
    this.base = normalizeBaseUrl(baseUrl);
  }

  baseUrl(): string {
    return this.base;
  }

  async otpRequest(email: string): Promise<void> {
    const res = await request(`${this.base}/v1/auth/otp/request`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: email.trim() }),
    });
    if (!res.ok) throw await mapError(res);
  }

  async otpVerify(email: string, code: string): Promise<Session> {
    const res = await request(`${this.base}/v1/auth/otp/verify`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: email.trim(), code: code.trim() }),
    });
    if (!res.ok) throw await mapError(res);
    return (await res.json()) as Session;
  }

  async oauthExchange(code: string): Promise<Session> {
    const res = await request(`${this.base}/v1/auth/oauth/exchange`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: code.trim() }),
    });
    if (!res.ok) throw await mapError(res);
    return (await res.json()) as Session;
  }

  async me(token: string): Promise<Account> {
    const res = await request(`${this.base}/v1/auth/me`, {
      headers: { authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw await mapError(res);
    return (await res.json()) as Account;
  }

  async listProjects(token: string): Promise<ProjectListItem[]> {
    const res = await request(`${this.base}/v1/companion/projects`, {
      headers: { authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw await mapError(res);
    const body = (await res.json()) as { projects: ProjectListItem[] };
    return body.projects;
  }

  async getProject(token: string, projectId: string): Promise<ProjectDetail> {
    const res = await request(`${this.base}/v1/companion/projects/${projectId}`, {
      headers: { authorization: `Bearer ${token}` },
    });
    if (!res.ok) throw await mapError(res);
    return (await res.json()) as ProjectDetail;
  }

  async listProposals(
    token: string,
    projectId: string,
    status?: string,
  ): Promise<Proposal[]> {
    const q = status !== undefined ? `?status=${encodeURIComponent(status)}` : "";
    const res = await request(
      `${this.base}/v1/companion/projects/${projectId}/proposals${q}`,
      { headers: { authorization: `Bearer ${token}` } },
    );
    if (!res.ok) throw await mapError(res);
    const body = (await res.json()) as { proposals: Proposal[] };
    return body.proposals;
  }

  async decideProposal(
    token: string,
    projectId: string,
    proposalId: string,
    decision: "approve" | "reject",
    note?: string,
  ): Promise<Proposal> {
    const res = await request(
      `${this.base}/v1/companion/projects/${projectId}/proposals/${proposalId}/decision`,
      {
        method: "POST",
        headers: {
          authorization: `Bearer ${token}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({ decision, note: note ?? null }),
      },
    );
    if (!res.ok) throw await mapError(res);
    return (await res.json()) as Proposal;
  }

  async getArtifactText(
    token: string,
    projectId: string,
    name: string,
  ): Promise<{ contentType: string; body: string }> {
    const res = await request(
      `${this.base}/v1/companion/projects/${projectId}/artifacts/${encodeURIComponent(name)}`,
      { headers: { authorization: `Bearer ${token}` } },
    );
    if (!res.ok) throw await mapError(res);
    const contentType = res.headers.get("content-type") ?? "application/octet-stream";
    const body = await res.text();
    return { contentType, body };
  }
}
