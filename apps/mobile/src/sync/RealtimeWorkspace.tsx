import type { JSX, PropsWithChildren } from "react";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { AppState } from "react-native";
import { TokitoPhysical } from "../../modules/tokito-physical/src";
import type { CloudClient, SyncResourceName } from "../native/bridge";
import { mergePhysicalSessionSnapshot, type PhysicalSessionsPayload } from "./physicalSync";
import { retryableStartup, startupRetryDelay } from "./startupRetry";
import { ownsWorkspaceEvent } from "./workspaceOwnership";
import { getTokitoCompanionFfi } from "../../modules/tokito-companion-ffi/src";

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
  onAuthorizationRevoked,
  children,
}: PropsWithChildren<{ client: CloudClient; token: string; onAuthorizationRevoked: () => void }>): JSX.Element {
  const [subscriptionId, setSubscriptionId] = useState<string | null>(null);
  const [status, setStatus] = useState<SyncStatus>(client.startSync ? "connecting" : "disabled");
  const [error, setError] = useState<string | null>(null);
  const [resourceMap, setResourceMap] = useState<Map<string, SyncResource>>(new Map());
  const [conflicts, setConflicts] = useState<SyncConflict[]>([]);
  const physicalDrain = useRef<Promise<void> | null>(null);
  const [startupAttempt, setStartupAttempt] = useState(0);

  useEffect(() => {
    const native = getTokitoCompanionFfi();
    const owner = `workspace-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    let disposed = false;
    const revoked = native.addListener("onAuthorizationRevoked", event => {
      if (ownsWorkspaceEvent(owner, event.owner, disposed)) onAuthorizationRevoked();
    });
    let timer: ReturnType<typeof setTimeout> | undefined;
    const open = async (): Promise<void> => {
      try { await native.startQueues(client.baseUrl(), token, owner); if (disposed) await native.stopQueues(owner); }
      catch (cause) {
        if (!disposed) {
          setError(retryableStartup(cause)
            ? "Saved messages and decisions are waiting for a connection."
            : "Saved messages and decisions could not reconnect. Sign in again to resume.");
          if (retryableStartup(cause)) timer = setTimeout(() => void open(), 5000);
        }
      }
    };
    void open();
    return () => { disposed = true; revoked.remove(); clearTimeout(timer); void native.stopQueues(owner); };
  }, [client, token, onAuthorizationRevoked]);

  useEffect(() => {
    if (!client.startSync || !client.addSyncListener || !client.stopSync) {
      setStatus("disabled");
      return;
    }
    let disposed = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let transientFailure = false;
    const foreground = AppState.addEventListener("change", next => {
      if (next === "active" && transientFailure && !disposed) {
        transientFailure = false;
        clearTimeout(retryTimer);
        setStartupAttempt(value => value + 1);
      }
    });
    let activeId: string | null = null;
    const earlyEvents: { subscriptionId: string; apply: () => void }[] = [];
    let earlyOverflow = false;
    setSubscriptionId(null);
    setStatus("connecting");
    setError(null);
    setResourceMap(new Map());
    setConflicts([]);
    const receive = (subscriptionId: string, apply: () => void): void => {
      if (disposed) return;
      if (activeId === null) {
        if (earlyEvents.length >= 256) { earlyOverflow = true; return; }
        earlyEvents.push({ subscriptionId, apply }); return;
      }
      if (subscriptionId === activeId) apply();
    };
    const subscriptions = [
      client.addSyncListener("onSyncStatus", (event) => {
        receive(event.subscriptionId, () => setStatus(event.status as SyncStatus));
      }),
      client.addSyncListener("onSyncBootstrap", (event) => {
        receive(event.subscriptionId, () => {
        const resources = parseJson<SyncResource[]>(event.resourcesJson);
        setResourceMap(new Map(resources.map((item) => [keyOf(item.project_id, item.design_id, item.resource), item])));
        setError(null);
        });
      }),
      client.addSyncListener("onSyncEvent", (event) => {
        receive(event.subscriptionId, () => {
        const item = parseJson<SyncEvent>(event.eventJson);
        setResourceMap((previous) => {
          const current = previous.get(keyOf(item.project_id, item.design_id, item.resource));
          if (current && current.version >= item.version) return previous;
          const next = new Map(previous);
          next.set(keyOf(item.project_id, item.design_id, item.resource), item);
          return next;
        });
        });
      }),
      client.addSyncListener("onSyncConflict", (event) => {
        receive(event.subscriptionId, () => {
        const command = parseJson<SyncConflict["command"]>(event.commandJson);
        setConflicts((previous) => [
          ...previous.filter((item) => item.command.command_id !== command.command_id),
          {
            command,
            currentVersion: Number(event.currentVersion),
            currentPayload: parseJson<unknown>(event.currentPayloadJson),
          },
        ]);
        });
      }),
      client.addSyncListener("onSyncError", (event) => {
        receive(event.subscriptionId, () => setError(event.message));
      }),
    ];
    void client.startSync(token).then((id) => {
      if (disposed) {
        void client.stopSync?.(id);
        return;
      }
      activeId = id;
      if (earlyOverflow) {
        earlyEvents.length = 0;
        setStatus("stopped");
        setError("Project updates could not initialize. Reopen Cloud projects to retry.");
        void client.stopSync?.(id);
        return;
      }
      for (const event of earlyEvents.splice(0)) receive(event.subscriptionId, event.apply);
      setSubscriptionId(id);
    }).catch((cause: unknown) => {
      if (!disposed) {
        transientFailure = retryableStartup(cause);
        setStatus(transientFailure ? "reconnecting" : "stopped");
        setError(cause instanceof Error ? cause.message : String(cause));
        if (transientFailure) retryTimer = setTimeout(() => {
          if (!disposed) setStartupAttempt(value => value + 1);
        }, startupRetryDelay(startupAttempt));
      }
    });
    return () => {
      disposed = true;
      clearTimeout(retryTimer);
      foreground.remove();
      earlyEvents.length = 0;
      subscriptions.forEach((item) => item.remove());
      if (activeId !== null) void client.stopSync?.(activeId);
    };
  }, [client, token, startupAttempt]);

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
    // Queued is not committed: only the authoritative journal updates the view.
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
