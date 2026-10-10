import type { ProjectListItem } from "../api/cloudHttp";

type ProjectResource = { project_id: string; design_id: string | null; resource: string; payload: unknown; last_activity_at?: number | null; created_at?: number };

function admittedActivity(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0 && value <= 8_640_000_000_000 ? value : undefined;
}

export function projectActivityTimestamp(project: ProjectListItem): number | null {
  const activity = admittedActivity(project.last_activity_at);
  if (activity !== undefined) return activity * 1000;
  const metadata = Date.parse(project.updated_at);
  return Number.isFinite(metadata) ? metadata : null;
}

export function formatProjectUpdatedAt(project: ProjectListItem, now = Date.now()): string {
  const timestamp = projectActivityTimestamp(project);
  if (timestamp === null) return "Update time unavailable";
  const seconds = Math.max(0, Math.floor((now - timestamp) / 1000));
  if (seconds < 60) return "Updated just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `Updated ${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `Updated ${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `Updated ${days}d ago`;
  return `Updated ${new Date(timestamp).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })}`;
}

/** Journal values override the published index, including explicit tombstones. */
export function mergeProjectList(published: ProjectListItem[], resources: ProjectResource[]): ProjectListItem[] {
  const projects = new Map(published.map(project => [project.id, project]));
  const deleted = new Set<string>();
  for (const resource of resources) {
    if (resource.resource !== "project" || resource.design_id !== null) continue;
    if (!resource.payload || typeof resource.payload !== "object") continue;
    const payload = resource.payload as Record<string, unknown>;
    if (payload.deleted === true) { projects.delete(resource.project_id); deleted.add(resource.project_id); continue; }
    if (deleted.has(resource.project_id)) continue;
    if (typeof payload.name !== "string") continue;
    const previous = projects.get(resource.project_id);
    projects.set(resource.project_id, {
      id: resource.project_id,
      name: payload.name,
      slug: typeof payload.slug === "string" ? payload.slug : previous?.slug ?? resource.project_id,
      updated_at: typeof payload.updated_at === "string" ? payload.updated_at : previous?.updated_at ?? "",
      last_activity_at: previous?.last_activity_at ?? null,
    });
  }
  // Only admitted server snapshots/events reach this input. Pending local commands
  // and read/refresh times must never fabricate project activity.
  for (const resource of resources) {
    const project = projects.get(resource.project_id);
    if (!project || deleted.has(resource.project_id)) continue;
    const activity = admittedActivity(resource.last_activity_at) ?? admittedActivity(resource.created_at);
    if (activity !== undefined && activity > (admittedActivity(project.last_activity_at) ?? 0)) {
      projects.set(project.id, { ...project, last_activity_at: activity });
    }
  }
  return [...projects.values()].sort((a, b) => (projectActivityTimestamp(b) ?? 0) - (projectActivityTimestamp(a) ?? 0) || a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
}
