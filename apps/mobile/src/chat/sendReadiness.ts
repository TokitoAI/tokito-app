export type SendReadiness = { hasSession: boolean; ready: boolean; busy: boolean; inFlight: boolean; blocked: boolean; needsSheet: boolean; hasText: boolean };
/** The button and action share one eligibility contract, including stale taps. */
export function sendBlockReason(state: SendReadiness): string | null {
  if (!state.hasSession || !state.ready || state.busy) return "Chat is not connected yet. Try again to reconnect; your draft is kept.";
  if (state.needsSheet) return "Choose a synchronized sheet before sending. Your draft is kept.";
  if (state.blocked) return "Restore the rejected message before sending another one.";
  if (state.inFlight) return "A message is already waiting to send or running.";
  if (!state.hasText) return "Write a message first.";
  return null;
}
