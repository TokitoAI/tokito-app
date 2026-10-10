export function changeCount(count: number): string {
  return `${count} ${count === 1 ? "change" : "changes"}`;
}

export function proposedChangeCount(count: number): string {
  return `${count} proposed ${count === 1 ? "change" : "changes"}`;
}

export function applyChangeLabel(count: number): string {
  return `Apply ${changeCount(count)}`;
}

export function savedDecisionCount(count: number): string {
  return `${count} saved ${count === 1 ? "decision" : "decisions"}`;
}

export function remainingElectricalIssues(count: number): string {
  return count === 0 ? "No electrical check issues reported."
    : `${count} electrical check ${count === 1 ? "issue remains" : "issues remain"}.`;
}
