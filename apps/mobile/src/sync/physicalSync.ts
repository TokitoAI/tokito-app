export type StructuredPhysicalSnapshot = {
  sessionId: string;
  payloadJson: string;
};

export type PhysicalSessionsPayload = {
  schema_version: 1;
  sessions: Record<string, unknown>;
};

export function mergePhysicalSessionSnapshot(
  current: unknown,
  snapshot: StructuredPhysicalSnapshot,
): PhysicalSessionsPayload {
  const prior = current !== null && typeof current === "object"
    ? current as Partial<PhysicalSessionsPayload>
    : {};
  const sessions = prior.sessions !== null && typeof prior.sessions === "object"
    ? prior.sessions
    : {};
  return {
    schema_version: 1,
    sessions: {
      ...sessions,
      [snapshot.sessionId]: JSON.parse(snapshot.payloadJson) as unknown,
    },
  };
}
