export function newStableId(): string {
  // RFC 4122 v4-shaped identifier. Workspace ids are identifiers rather than
  // secrets; the native/server command layer still supplies authenticated,
  // idempotent mutation ids.
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (token) => {
    const value = Math.floor(Math.random() * 16);
    const nibble = token === "x" ? value : (value & 0x3) | 0x8;
    return nibble.toString(16);
  });
}
