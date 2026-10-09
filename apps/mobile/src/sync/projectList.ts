import type { ProjectListItem } from "../api/cloudHttp";

type ProjectResource = { project_id: string; design_id: string | null; resource: string; payload: unknown };

/** Journal values override the published index, including explicit tombstones. */
export function mergeProjectList(published: ProjectListItem[], resources: ProjectResource[]): ProjectListItem[] {
  const projects = new Map(published.map(project => [project.id, project]));
  for (const resource of resources) {
    if (resource.resource !== "project" || resource.design_id !== null) continue;
    if (!resource.payload || typeof resource.payload !== "object") continue;
    const payload = resource.payload as Record<string, unknown>;
    if (payload.deleted === true) { projects.delete(resource.project_id); continue; }
    if (typeof payload.name !== "string") continue;
    const previous = projects.get(resource.project_id);
    projects.set(resource.project_id, {
      id: resource.project_id,
      name: payload.name,
      slug: typeof payload.slug === "string" ? payload.slug : previous?.slug ?? resource.project_id,
      updated_at: typeof payload.updated_at === "string" ? payload.updated_at : previous?.updated_at ?? "",
    });
  }
  const timestamp = (value: string): number => { const parsed = Date.parse(value); return Number.isFinite(parsed) ? parsed : 0; };
  return [...projects.values()].sort((a, b) => timestamp(b.updated_at) - timestamp(a.updated_at) || a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
}
