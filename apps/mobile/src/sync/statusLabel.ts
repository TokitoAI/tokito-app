import type { SyncStatus } from "./RealtimeWorkspace";

export function projectUpdatesLabel(status: SyncStatus, compact = false): string {
  const prefix = compact ? "Updates" : "Project updates";
  switch (status) {
    case "live": return `${prefix} connected`;
    case "connecting": return `${prefix} connecting…`;
    case "reconnecting": return `${prefix} reconnecting…`;
    case "disabled": return `${prefix} unavailable`;
    case "stopped": return `${prefix} offline`;
  }
}
