import type { JSX } from "react";
import { useEffect, useRef, useState } from "react";
import { ActivityIndicator, Alert, AppState, Pressable, StyleSheet, Text, View } from "react-native";
import { getTokitoCompanionFfi } from "../../modules/tokito-companion-ffi/src";
import { tokens } from "../theme";
import { Card, ErrorText, Muted, PrimaryButton } from "../ui/primitives";
import { ReviewClient, parseReceipt, reviewFailure, type Proposal, type Receipt, type ReviewCommand } from "./reviewClient";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";
import { applyChangeLabel, changeCount, remainingElectricalIssues, savedDecisionCount } from "./reviewCopy";

export function ProjectReview({ base, token, project, design, revision, bomRevision, summaryVersion }: { base: string; token: string; project: string; design: string; revision: number; bomRevision: number; summaryVersion: number }): JSX.Element {
  const { owner } = useRealtimeWorkspace();
  const client = useRef<ReviewClient | null>(null);
  const request = useRef(0);
  const operation = useRef(false);
  const refreshQueued = useRef(false);
  const [proposals, setProposals] = useState<Proposal[]>([]);
  const [commands, setCommands] = useState<ReviewCommand[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [retry, setRetry] = useState(0);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [cached, setCached] = useState(false);
  async function refresh(current: ReviewClient): Promise<void> {
    if (operation.current) { refreshQueued.current = true; return; }
    operation.current = true;
    const ticket = ++request.current;
    setBusy(true);
    try { const result = await current.load(project, design); if (client.current !== current || ticket !== request.current) return; setProposals(result.proposals); setCommands(result.commands); setCached(result.cached); setError(null); }
    catch { if (client.current !== current || ticket !== request.current) return; setError("Reviews could not be refreshed. Any saved decision remains queued; retrying will not apply it twice."); try { const local = await current.local(project, design); if (client.current === current && ticket === request.current) setCommands(local); } catch { /* Keep last known queue. */ } }
    finally { if (client.current === current && ticket === request.current) { operation.current = false; setBusy(false); if (refreshQueued.current) { refreshQueued.current = false; void refresh(current); } } }
  }
  useEffect(() => {
    if (owner === null) return;
    const current = new ReviewClient(getTokitoCompanionFfi()); client.current = current;
    operation.current = false;
    refreshQueued.current = false;
    setReady(false); setProposals([]); setCommands([]); setError(null); setReceipt(null); setCached(false);
    void current.open(base, token, owner).then(async () => { if (client.current !== current) return; setReady(true); await refresh(current); }).catch(() => { if (client.current === current) setError("Review service could not be opened. Check your connection and sign-in, then retry."); });
    return () => { client.current = null; request.current++; void current.close(); };
    // Retry deliberately recreates the authenticated native session.
  }, [base, token, owner, project, design, retry]);
  useEffect(() => { if (ready && client.current) void refresh(client.current); }, [summaryVersion]);
  async function decide(proposal: Proposal, action: "apply" | "reject", selection: number[]): Promise<void> {
    const current = client.current; if (!current || operation.current) return;
    operation.current = true;
    ++request.current; setBusy(true); setError(null); setReceipt(null);
    let confirmed = false;
    let rejection: string | undefined;
    try { const result = await current.decide(project, design, proposal, action, selection); if (client.current !== current) return; rejection = result.failure; if (result.failure) setError(result.failure); if (result.receipt) { confirmed = true; setReceipt(result.receipt); }
      const refreshed = await current.load(project, design); if (client.current !== current) return; setProposals(refreshed.proposals); setCommands(refreshed.commands); setCached(refreshed.cached);
    } catch { if (client.current === current) { setError(rejection ?? (confirmed ? "Your decision was confirmed, but the review list could not be refreshed." : "The server has not confirmed this decision. Refresh to check its status before trying again.")); try { const local = await current.local(project, design); if (client.current === current) setCommands(local); } catch { /* Recovery remains in durable native storage. */ } } }
    finally { if (client.current === current) { operation.current = false; setBusy(false); if (refreshQueued.current) { refreshQueued.current = false; void refresh(current); } } }
  }
  const pending = commands.filter(c => c.state === "pending");
  useEffect(() => {
    if (!ready) return;
    const subscription = AppState.addEventListener("change", state => { if (state === "active" && client.current) void refresh(client.current); });
    const timer = pending.length > 0 ? setInterval(() => { if (AppState.currentState === "active" && client.current) void refresh(client.current); }, 10000) : null;
    return () => { subscription.remove(); if (timer !== null) clearInterval(timer); };
  }, [ready, pending.length]);
  const recent = commands.find(c => c.state === "confirmed");
  let displayedReceipt = receipt;
  if (!displayedReceipt && recent) { try { displayedReceipt = parseReceipt(recent.result); } catch { /* Never manufacture success from malformed data. */ } }
  return <View style={styles.root}>
    <View style={styles.heading}><View style={styles.flex}><Text style={styles.title}>Review changes</Text><Muted>Choose changes before applying them to this design.</Muted></View><Pressable accessibilityRole="button" disabled={busy} onPress={() => setRetry(v => v + 1)} style={styles.refresh}><Text style={styles.link}>Refresh</Text></Pressable></View>
    {busy ? <ActivityIndicator color={tokens.accent} /> : null}{error ? <ErrorText>{error}</ErrorText> : null}
    {cached ? <Card><Muted>Saved reviews · checking for updates. Decisions synchronize when your connection returns.</Muted></Card> : null}
    {pending.length > 0 ? <Card><Text style={styles.cardTitle}>Waiting for confirmation</Text><Muted>{savedDecisionCount(pending.length)}. Tokito retries automatically when connected.</Muted></Card> : null}
    {commands.filter(c => c.state === "blocked").slice(0, 1).map(c => <Card key={c.request.command_id}><ErrorText>{reviewFailure(c.result)}</ErrorText></Card>)}
    {displayedReceipt ? <ReceiptCard receipt={displayedReceipt} /> : null}
    {ready && !busy && !error && proposals.filter(p => p.status === "pending").length === 0 ? <Card><Text style={styles.cardTitle}>No changes waiting for review</Text><Muted>Proposals from this design’s chat will appear here.</Muted></Card> : null}
    {proposals.filter(p => p.status === "pending").map(p => <ProposalCard key={`${p.run_id}:${p.event_seq}`} proposal={p} stale={p.base_schematic_version !== revision || p.base_bom_version !== bomRevision} disabled={busy || pending.some(c => c.request.run_id === p.run_id && c.request.event_seq === p.event_seq)} onDecide={(action, selected) => void decide(p, action, selected)} />)}
  </View>;
}
function ReceiptCard({ receipt }: { receipt: Receipt }): JSX.Element {
  const result = receipt.result;
  return <Card><Text style={styles.cardTitle}>{result.status === "rejected" ? "Proposal rejected" : "Changes applied"}</Text>{result.status === "applied" ? <><Muted>{result.applied.length} applied · {result.unchanged.length} unchanged · 0 rejected</Muted><Text style={styles.body}>{result.erc === null ? "Electrical checks were not included in this result." : remainingElectricalIssues(result.erc.length)}</Text>{result.erc?.slice(0, 10).map((issue, index) => <Text key={index} style={styles.issue}>{issue.severity}: {issue.message}</Text>)}{(result.erc?.length ?? 0) > 10 ? <Muted>Open the desktop electrical checks for the full report.</Muted> : null}</> : <Muted>No schematic or BOM changes were made.</Muted>}</Card>;
}
function ProposalCard({ proposal: p, stale, disabled, onDecide }: { proposal: Proposal; stale: boolean; disabled: boolean; onDecide: (action: "apply" | "reject", selection: number[]) => void }): JSX.Element {
  const [selected, setSelected] = useState(() => new Set(p.payload.ops.map((_, i) => i)));
  const [visible, setVisible] = useState(25);
  const invalid = !!p.payload.failure_note || stale;
  return <Card><View style={styles.heading}><Text style={styles.cardTitle}>{p.payload.kind === "bom" ? "Bill of materials" : "Schematic changes"}</Text><Text style={styles.count}>{changeCount(p.payload.ops.length)}</Text></View>
    {p.payload.summary ? <Text style={styles.body}>{p.payload.summary}</Text> : null}
    {p.active_sheet_id ? <Muted>Sheet: {p.active_sheet_id}</Muted> : null}
    {invalid ? <ErrorText>{stale ? "This proposal is based on an older design. Ask for an updated proposal before applying." : p.payload.failure_note ?? "This proposal cannot be applied."}</ErrorText> : null}
    <View style={styles.heading}><Pressable accessibilityRole="button" disabled={disabled} onPress={() => setSelected(new Set(p.payload.ops.map((_, i) => i)))} style={styles.utility}><Text style={styles.link}>Select all</Text></Pressable><Pressable accessibilityRole="button" disabled={disabled} onPress={() => setSelected(new Set())} style={styles.utility}><Text style={styles.link}>Clear</Text></Pressable></View>
    {p.payload.ops.slice(0, visible).map((op, i) => <Pressable key={i} accessibilityRole="checkbox" accessibilityState={{ checked: selected.has(i), disabled }} disabled={disabled} onPress={() => setSelected(old => { const next = new Set(old); if (next.has(i)) next.delete(i); else next.add(i); return next; })} style={styles.operation}><View style={[styles.check, selected.has(i) && styles.checked]}><Text style={styles.checkText}>{selected.has(i) ? "✓" : ""}</Text></View><View style={styles.flex}><Text style={styles.body}>{typeof op.summary === "string" && op.summary ? op.summary : String(op.op ?? "Change").replaceAll("_", " ")}</Text>{typeof op.ref_des === "string" ? <Muted>{op.ref_des}{typeof op.value === "string" ? ` · ${op.value}` : ""}</Muted> : null}</View></Pressable>)}
    {visible < p.payload.ops.length ? <Pressable accessibilityRole="button" onPress={() => setVisible(v => v + 25)} style={styles.utility}><Text style={styles.link}>Show more changes</Text></Pressable> : null}
    <View style={styles.actions}><PrimaryButton label={applyChangeLabel(selected.size)} disabled={disabled || invalid || selected.size === 0} onPress={() => onDecide("apply", [...selected].sort((a, b) => a - b))} style={styles.flex} /><Pressable accessibilityRole="button" disabled={disabled} style={styles.reject} onPress={() => Alert.alert("Reject this proposal?", "No design changes will be made.", [{ text: "Keep reviewing", style: "cancel" }, { text: "Reject", style: "destructive", onPress: () => onDecide("reject", []) }])}><Text style={styles.rejectText}>Reject</Text></Pressable></View>
  </Card>;
}
const styles = StyleSheet.create({ root: { gap: 14, width: "100%", maxWidth: 1100, alignSelf: "center" }, heading: { flexDirection: "row", alignItems: "center", gap: 12 }, flex: { flex: 1, minWidth: 0 }, title: { fontSize: 20, fontWeight: "700", color: tokens.text }, cardTitle: { fontSize: 16, fontWeight: "600", color: tokens.text, marginBottom: 8 }, body: { fontSize: 14, lineHeight: 21, color: tokens.text }, link: { color: tokens.accent, fontSize: 14, fontWeight: "600" }, refresh: { minHeight: 48, paddingHorizontal: 10, justifyContent: "center" }, count: { color: tokens.text2, fontSize: 12, marginLeft: "auto" }, utility: { minHeight: 48, paddingHorizontal: 10, justifyContent: "center" }, operation: { minHeight: 56, flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: tokens.border }, check: { width: 24, height: 24, borderWidth: 1, borderColor: tokens.borderStrong, borderRadius: 6, alignItems: "center", justifyContent: "center" }, checked: { backgroundColor: tokens.accent, borderColor: tokens.accent }, checkText: { color: tokens.accentInk, fontWeight: "700" }, actions: { flexDirection: "row", gap: 12, marginTop: 16 }, reject: { minHeight: 48, paddingHorizontal: 20, justifyContent: "center" }, rejectText: { color: tokens.danger, fontWeight: "600" }, issue: { fontSize: 13, lineHeight: 19, color: tokens.warning, marginTop: 6 } });
