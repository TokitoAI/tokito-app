import type { SyncEvent, SyncResource } from "./RealtimeWorkspace";

/** Content revision and committed recency are independent server facts. */
export function mergeCommittedResourceEvent(current: SyncResource | undefined, event: SyncEvent): SyncResource {
  if (current && current.version >= event.version) return current;
  return { ...event, last_activity_at: Math.max(current?.last_activity_at ?? 0, event.created_at) };
}
