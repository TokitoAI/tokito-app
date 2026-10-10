/** Assistant content gets the available width; only user messages are bubbles. */
export const chatCardLayout = {
  assistant: { alignSelf: "stretch", maxWidth: "100%" },
  user: { alignSelf: "flex-end", maxWidth: "85%" },
  // A vertical document must contribute its entire natural height to its card.
  document: { alignSelf: "stretch", flexShrink: 0 },
  listText: { flexGrow: 1, flexShrink: 1, flexBasis: 0, minWidth: 0 },
} as const;
