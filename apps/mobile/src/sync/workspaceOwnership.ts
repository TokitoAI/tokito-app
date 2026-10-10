/** Events already queued by native may arrive after account/navigation cleanup. */
export function ownsWorkspaceEvent(owner: string, eventOwner: unknown, disposed: boolean): boolean {
  return !disposed && typeof eventOwner === "string" && eventOwner === owner;
}
