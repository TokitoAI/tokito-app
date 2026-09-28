import type { JSX, PropsWithChildren } from "react";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { AppState } from "react-native";
import { TokitoPhysical } from "../../modules/tokito-physical/src";
import type { CloudClient, SyncResourceName } from "../native/bridge";
import { mergePhysicalSessionSnapshot, type PhysicalSessionsPayload } from "./physicalSync";

export type SyncResource = {
  project_id: string;
  design_id: string | null;
  resource: SyncResourceName;
  version: number;
  payload: unknown;
};

export type SyncEvent = SyncResource & {
  seq: number;
  command_id: string;
  actor_device_id: string;
  created_at: number;
};

export type SyncConflict = {
  command: {
    command_id: string;
    project_id: string;
    design_id: string | null;
    resource: SyncResourceName;
    payload: unknown;
  };
  currentVersion: number;
  currentPayload: unknown;
};

export type SyncStatus = "disabled" | "connecting" | "live" | "reconnecting" | "stopped";

type WorkspaceValue = {
  status: SyncStatus;
  error: string | null;
  resources: SyncResource[];
  conflicts: SyncConflict[];
  getResource(projectId: string, designId: string | null, resource: SyncResourceName): SyncResource | undefined;
  update(projectId: string, designId: string | null, resource: CompanionWritableResource, payload: unknown): Promise<string>;
  resolveConflict(commandId: string, keepLocal: boolean): Promise<void>;
};

/** Mobile authors conversation and explicit review decisions, never CAD data. */
export type CompanionWritableResource = Extract<SyncResourceName, "chat" | "suggestions">;

const WorkspaceContext = createContext<WorkspaceValue | null>(null);

function keyOf(projectId: string, designId: string | null, resource: string): string {
  return `${projectId}\u0000${designId ?? ""}\u0000${resource}`;
}

function parseJson<T>(value: string): T {
  return JSON.parse(value) as T;
}

export function RealtimeWorkspaceProvider({
  client,
  token,
  children,
}: PropsWithChildren<{ client: CloudClient; token: string }>): JSX.Element {
  const [subscriptionId, setSubscriptionId] = useState<string | null>(null);
  const [status, setStatus] = useState<SyncStatus>(client.startSync ? "connecting" : "disabled");
  const [error, setError] = useState<string | null>(null);
  const [resourceMap, setResourceMap] = useState<Map<string, SyncResource>>(new Map());
  const [conflicts, setConflicts] = useState<SyncConflict[]>([]);
  const physicalDrain = useRef<Promise<void> | null>(null);

  useEffect(() => {
    if (!client.startSync || !client.addSyncListener || !client.stopSync) {
      setStatus("disabled");
      return;
    }
    let disposed = false;
    let activeId: string | null = null;
    const subscriptions = [
      client.addSyncListener("onSyncStatus", (event) => {
        if (!disposed) setStatus(event.status as SyncStatus);
      }),
      client.addSyncListener("onSyncBootstrap", (event) => {
        if (disposed) return;
        const resources = parseJson<SyncResource[]>(event.resourcesJson);
        setResourceMap(new Map(resources.map((item) => [keyOf(item.project_id, item.design_id, item.resource), item])));
        setError(null);
      }),
      client.addSyncListener("onSyncEvent", (event) => {
        if (disposed) return;
        const item = parseJson<SyncEvent>(event.eventJson);
        setResourceMap((previous) => {
          const next = new Map(previous);
          next.set(keyOf(item.project_id, item.design_id, item.resource), item);
          return next;
        });
      }),
      client.addSyncListener("onSyncConflict", (event) => {
        if (disposed) return;
        const command = parseJson<SyncConflict["command"]>(event.commandJson);
        setConflicts((previous) => [
          ...previous.filter((item) => item.command.command_id !== command.command_id),
          {
            command,
            currentVersion: Number(event.currentVersion),
            currentPayload: parseJson<unknown>(event.currentPayloadJson),
          },
        ]);
      }),
      client.addSyncListener("onSyncError", (event) => {
        if (!disposed) setError(event.message);
      }),
    ];
    void client.startSync(token).then((id) => {
      if (disposed) {
        void client.stopSync?.(id);
        return;
      }
      activeId = id;
      setSubscriptionId(id);
    }).catch((cause: unknown) => {
      if (!disposed) {
        setStatus("stopped");
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    });
    return () => {
      disposed = true;
      subscriptions.forEach((item) => item.remove());
      if (activeId !== null) void client.stopSync?.(activeId);
    };
  }, [client, token]);

  const getResource = useCallback(
    (projectId: string, designId: string | null, resource: SyncResourceName) =>
      resourceMap.get(keyOf(projectId, designId, resource)),
    [resourceMap],
  );

  const update = useCallback(async (
    projectId: string,
    designId: string | null,
    resource: CompanionWritableResource,
    payload: unknown,
  ): Promise<string> => {
    if (resource !== "chat" && resource !== "suggestions") {
      throw new Error(`Companion cannot author the ${String(resource)} resource`);
    }
    if (subscriptionId === null || !client.enqueueSyncCommand) {
      throw new Error("Realtime sync is not connected yet");
    }
    const commandId = await client.enqueueSyncCommand(
      subscriptionId,
      projectId,
      designId,
      resource,
      payload,
    );
    setResourceMap((previous) => {
      const next = new Map(previous);
      const key = keyOf(projectId, designId, resource);
      const existing = previous.get(key);
      next.set(key, {
        project_id: projectId,
        design_id: designId,
        resource,
        version: existing?.version ?? 0,
        payload,
      });
      return next;
    });
    return commandId;
  }, [client, subscriptionId]);

  const drainPhysicalSync = useCallback(async (): Promise<void> => {
    if (physicalDrain.current !== null) return physicalDrain.current;
    if (subscriptionId === null || !client.enqueueSyncCommand) return;
    const enqueueSyncCommand = client.enqueueSyncCommand.bind(client);
    const run = (async () => {
      const snapshots = await TokitoPhysical.pendingSyncSnapshots();
      const mergedByResource = new Map<string, PhysicalSessionsPayload>();
      for (const snapshot of snapshots) {
        const key = keyOf(snapshot.projectId, snapshot.designId, "physical_sessions");
        const current = mergedByResource.get(key)
          ?? resourceMap.get(key)?.payload;
        const merged = mergePhysicalSessionSnapshot(current, snapshot);
        await enqueueSyncCommand(
          subscriptionId,
          snapshot.projectId,
          snapshot.designId,
          "physical_sessions",
          merged,
        );
        // enqueueSyncCommand returns only after the Rust outbox durably owns the
        // command. Until then the native queue remains untouched and retryable.
        await TokitoPhysical.markSyncEnqueued(snapshot.sessionId);
        mergedByResource.set(key, merged);
      }
    })();
    physicalDrain.current = run;
    try {
      await run;
    } finally {
      physicalDrain.current = null;
    }
  }, [client, resourceMap, subscriptionId]);

  useEffect(() => {
    if (subscriptionId === null) return;
    void drainPhysicalSync().catch((cause: unknown) => {
      setError(cause instanceof Error ? cause.message : String(cause));
    });
    const listener = AppState.addEventListener("change", (next) => {
      if (next === "active") {
        void drainPhysicalSync().catch((cause: unknown) => {
          setError(cause instanceof Error ? cause.message : String(cause));
        });
      }
    });
    return () => listener.remove();
  }, [drainPhysicalSync, subscriptionId]);

  const resolveConflict = useCallback(async (commandId: string, keepLocal: boolean) => {
    if (subscriptionId === null || !client.resolveSyncConflict) return;
    await client.resolveSyncConflict(subscriptionId, commandId, keepLocal);
    setConflicts((previous) => previous.filter((item) => item.command.command_id !== commandId));
  }, [client, subscriptionId]);

  const value = useMemo<WorkspaceValue>(() => ({
    status,
    error,
    resources: [...resourceMap.values()],
    conflicts,
    getResource,
    update,
    resolveConflict,
  }), [status, error, resourceMap, conflicts, getResource, update, resolveConflict]);

  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useRealtimeWorkspace(): WorkspaceValue {
  const value = useContext(WorkspaceContext);
  if (value === null) throw new Error("RealtimeWorkspaceProvider is missing");
  return value;
}
