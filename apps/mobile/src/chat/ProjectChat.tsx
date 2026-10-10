import type { JSX } from "react";
import { useEffect, useRef, useState } from "react";
import { ActivityIndicator, FlatList, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { getTokitoCompanionFfi } from "../../modules/tokito-companion-ffi/src";
import { ConversationClient, conversationTitle } from "./conversationClient";
import { chatFailure, ConversationScope } from "./conversationState";
import { belongsToDisplayedRun, chatHistoryProjection, emptyRun, isActive, object, parseRun, reduceRun, string, type ChatRow, type ChatRun, type ChatThread, type RunView } from "./conversationModel";
import { tokens } from "../theme";
import { ErrorText, PrimaryButton } from "../ui/primitives";
import { selectedSheet, type ChatSheet } from "./sheetContext";
import { ChatMarkdown } from "./ChatMarkdownView";
import { ChatFollowFrame, ChatScrollIntent } from "./chatScroll";
import { chatCardLayout } from "./chatLayout";
import { sendBlockReason } from "./sendReadiness";
import { chatSendFailure, chatSendStep, type ChatSendStage } from "./sendDiagnostics";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";

type Props = { baseUrl: string; token: string; project: string; design: string; version: number; sheets: ChatSheet[]; onReview: () => void };
export function ProjectChat({ baseUrl, token, project, design, version, sheets, onReview }: Props): JSX.Element {
  const { owner } = useRealtimeWorkspace();
  const [sheetChoice, setSheetChoice] = useState<string | null>(null);
  const activeSheetId = selectedSheet(sheets, sheetChoice);
  const needsSheet = version > 0 && activeSheetId === null;
  const connection = useRef<ConversationClient | null>(null);
  const selected = useRef<string | null>(null);
  const list = useRef<FlatList<ChatRow>>(null);
  const scrollIntent = useRef(new ChatScrollIntent());
  const followFrame = useRef<ChatFollowFrame | null>(null);
  if (followFrame.current === null) followFrame.current = new ChatFollowFrame(
    scrollIntent.current, requestAnimationFrame, cancelAnimationFrame,
    (offset, animated) => list.current?.scrollToOffset({ offset, animated }),
  );
  useEffect(() => () => followFrame.current?.cancel(), []);
  const [showLatest, setShowLatest] = useState(false);
  const displayedRun = useRef<string | null>(null);
  const [threads, setThreads] = useState<ChatThread[]>([]);
  const [thread, setThread] = useState<string | null>(null);
  const [rows, setRows] = useState<ChatRow[]>([]);
  const [older, setOlder] = useState<number | null>(null);
  const [run, setRun] = useState<ChatRun | null>(null);
  const [savedRun, setSavedRun] = useState<string | null>(null);
  const [view, setView] = useState<RunView>(emptyRun);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [blocked, setBlocked] = useState<{ id: string; content: string } | null>(null);
  const epoch = useRef(0);
  const acceptedMessage = useRef<string | null>(null);
  const scope = useRef(new ConversationScope());
  const historyRequest = useRef(0);
  const scopeKey = useRef("");
  const sendingTicket = useRef<number | null>(null);
  const [ready, setReady] = useState(false);
  function updateDraft(text: string): void { scope.current.save(selected.current, text); setDraft(text); }
  function followLatest(animated = false): void {
    followFrame.current?.request(animated);
  }

  async function load(client: ConversationClient, id: string, before: number | null = null): Promise<void> {
    const ticket = scope.current.ticket();
    const request = ++historyRequest.current;
    const page = await client.history(project, design, id, before);
    if (connection.current !== client || selected.current !== id || !scope.current.current(ticket) || request !== historyRequest.current) return;
    if (before === null) { scrollIntent.current.open(); setShowLatest(false); }
    setOlder(page.olderCursor);
    setRows(prior => before === null ? page.messages : [...page.messages, ...prior.filter(r => !page.messages.some(m => m.id === r.id))]);
    if (before === null) {
      const [pending, recovered] = await Promise.all([client.queued(id), client.recoveredDraft(id)]);
      if (connection.current !== client || selected.current !== id || !scope.current.current(ticket) || request !== historyRequest.current) return;
      setBlocked(pending?.blocked ? pending : null);
      setSending(pending !== null && !pending.blocked);
      if (pending) {
        setRows(prior => prior.some(row => row.id === pending.id) ? prior : [...prior, { id: pending.id, runId: pending.runId, role: "user", content: pending.content }]);
        setNotice(pending.blocked ? null : "Message saved. Waiting for a connection…");
      }
      if (recovered !== null && !scope.current.draft(id)) updateDraft(recovered);
      const currentRun = pending && pending.runId !== page.latestRun?.run_id ? null : page.latestRun;
      setRun(currentRun);
      setSavedRun(currentRun && !isActive(currentRun) ? currentRun.run_id : null);
      setView(emptyRun());
      displayedRun.current = currentRun?.run_id ?? null;
      if (currentRun !== null) await client.watch(currentRun.run_id);
    }
  }

  useEffect(() => {
    if (owner === null) return;
    const id = ++epoch.current;
    let client: ConversationClient;
    try { client = new ConversationClient(getTokitoCompanionFfi()); }
    catch { setError("Install the current Tokito app to open design chat."); setBusy(false); return; }
    connection.current = client;
    const key = JSON.stringify([baseUrl, token, project, design]);
    const resumeThread = scopeKey.current === key ? selected.current : null;
    if (scopeKey.current !== key) { scope.current.reset(); scopeKey.current = key; }
    else scope.current.next();
    sendingTicket.current = null;
    setDraft(scope.current.draft(null)); setReady(false); setSending(false); setBlocked(null); setNotice(null);
    selected.current = null;
    displayedRun.current = null; setSavedRun(null); scrollIntent.current.open(); setShowLatest(false);
    setThread(null); setRows([]); setOlder(null); setRun(null); setView(emptyRun()); setBusy(true); setError(null);
    const current = () => epoch.current === id && connection.current === client;
    void client.open(baseUrl, token, change => {
      if (!current()) return;
      if (change.type === "accepted") {
        const accepted = parseRun(change.run);
        if (accepted.thread_id !== selected.current) return;
        acceptedMessage.current = accepted.run_id;
        historyRequest.current++;
        setRun(accepted); setNotice(null); setSending(false);
        setSavedRun(null);
        displayedRun.current = accepted.run_id;
        setView(emptyRun());
        const ticket = scope.current.ticket();
        void client.watch(accepted.run_id).catch(error => { if (current() && scope.current.current(ticket)) setError(chatFailure(error)); });
      } else if (change.type === "event") {
        if (!belongsToDisplayedRun(change.run_id, displayedRun.current)) return;
        setView(previous => {
          try { return reduceRun(previous, change.event); }
          catch { return { ...previous, error: "Live response could not be displayed. Reopen this conversation to recover it." }; }
        });
      } else if (change.type === "run_snapshot") {
        const snapshot = parseRun(change.run);
        if (snapshot.thread_id !== selected.current || snapshot.run_id !== displayedRun.current) return;
        setRun(snapshot); setSending(false); setNotice(null);
      } else if (change.type === "settled") {
        const settled = parseRun(change.run);
        if (settled.thread_id !== selected.current || settled.run_id !== displayedRun.current) return;
        setRun(settled); setSending(false); setNotice(null);
        const ticket = scope.current.ticket();
        const request = ++historyRequest.current;
        void client.history(project, design, settled.thread_id).then(page => {
          if (current() && scope.current.current(ticket) && request === historyRequest.current && selected.current === settled.thread_id) {
            setRows(prior => [...prior.filter(row => !page.messages.some(message => message.id === row.id)), ...page.messages]);
            setSavedRun(settled.run_id);
          }
        }).catch(error => { if (current() && scope.current.current(ticket)) setError(chatFailure(error)); });
      } else if (change.type === "offline" || change.type === "reconnecting") setNotice("Connection interrupted. Retrying without sending twice…");
      else if (change.type === "blocked") {
        const message = object(change.message);
        if (message.thread_id !== selected.current) return;
        const request = object(message.request);
        setBlocked({ id: string(request.message_id), content: string(request.content) });
        setSending(false); setError("The design changed before this message could start. Review the latest design before retrying.");
      }
      else if (change.type === "unauthorized") { setSending(false); setError("Your session expired. Sign in again to continue."); }
      else { setSending(false); setError("Chat could not update. Reopen the conversation to recover your saved messages."); }
    }, owner).then(async () => {
      if (!current()) return;
      const items = await client.threads(project, design);
      if (!current()) return;
      setThreads(items);
      setReady(true);
      const first = items.find(item => item.id === resumeThread) ?? items[0];
      if (first) { selected.current = first.id; setThread(first.id); setDraft(scope.current.draft(first.id)); await load(client, first.id); }
    }).catch(error => { if (current()) setError(chatFailure(error)); })
      .finally(() => { if (current()) setBusy(false); });
    return () => { epoch.current++; connection.current = null; void client.close().catch(() => {}); };
    // The session owns its callbacks for this exact account/project scope.
  }, [baseUrl, token, project, design, retry, owner]);

  async function choose(id: string | null): Promise<void> {
    const client = connection.current;
    if (!client) return;
    const ticket = scope.current.next();
    sendingTicket.current = null;
    displayedRun.current = null; setSavedRun(null); scrollIntent.current.open(); setShowLatest(false);
    setDraft(scope.current.draft(id));
    selected.current = id; acceptedMessage.current = null; setThread(id); setRows([]); setOlder(null); setView(emptyRun()); setRun(null); setError(null); setNotice(null); setBlocked(null); setSending(false); setBusy(true);
    try { await client.stopWatching(); if (scope.current.current(ticket) && id !== null) await load(client, id); }
    catch (error) { if (connection.current === client && scope.current.current(ticket)) setError(chatFailure(error)); }
    finally { if (connection.current === client && scope.current.current(ticket)) setBusy(false); }
  }
  async function send(): Promise<void> {
    const client = connection.current;
    const content = draft.trim();
    const problem = sendBlockReason({hasSession:client !== null,ready,busy,inFlight:sending || sendingTicket.current !== null || isActive(run),blocked:blocked !== null,needsSheet,hasText:!!content});
    if (problem !== null) { setError(problem); return; }
    if (client === null) return; // Type narrowing; eligibility rejects this above.
    const ticket = scope.current.ticket();
    sendingTicket.current = ticket;
    setSending(true); setError(null);
    // Stop projecting the previous run before the new queued user turn exists.
    displayedRun.current = null; setSavedRun(null); setRun(null); setView(emptyRun());
    scrollIntent.current.latest(); setShowLatest(false);
    let stage: ChatSendStage = "prepare";
    try {
      const { firstCommand, firstVersion } = await chatSendStep("prepare", async () => {
        const title = conversationTitle(content);
        const firstCommand = selected.current === null
          ? scope.current.firstMessageCommand(JSON.stringify([project, design, title, content, activeSheetId, "planner"]), () => client.newCommand(), version)
          : null;
        const firstVersion = firstCommand === null ? version : scope.current.firstMessageVersion(firstCommand);
        return { firstCommand, firstVersion };
      });
      stage = "stopWatching";
      await chatSendStep(stage, () => client.stopWatching());
      if (connection.current !== client || !scope.current.current(ticket)) return;
      let id = selected.current;
      let pending: Record<string, unknown>;
      stage = "send";
      if (id === null) {
        if (firstCommand === null) throw new Error("Conversation changed before sending");
        const receipt = await client.startChat(firstCommand, project, design, content, firstVersion, activeSheetId);
        stage = "receipt";
        // Commit owns the original text even when navigation finishes before
        // the receipt. Retain a newer draft/command; never clear another scope.
        const ownsDraft = scope.current.finishFirstMessage(firstCommand);
        if (ownsDraft && scopeKey.current === JSON.stringify([baseUrl, token, project, design]) && scope.current.draft(null).trim() === content) scope.current.save(null, "");
        if (connection.current !== client || !scope.current.current(ticket) || selected.current !== null) return;
        id = receipt.thread.id; selected.current = id; setThread(id);
        setThreads(items => items.some(item => item.id === id) ? items : [receipt.thread, ...items]);
        pending = receipt.message;
      } else pending = await client.sendChat(project, design, id, content, version, activeSheetId);
      stage = "receipt";
      // A successful durable enqueue owns this text, even if navigation finished
      // while it was pending. Do not leave a second sendable copy in its draft.
      if (scopeKey.current === JSON.stringify([baseUrl, token, project, design]) && scope.current.draft(id).trim() === content) scope.current.save(id, "");
      if (connection.current !== client || !scope.current.current(ticket) || selected.current !== id) return;
      stage = "queued";
      const { request, runId, messageId } = await chatSendStep(stage, async () => {
        const request = object(pending.request);
        return { request, runId: string(request.run_id), messageId: string(request.message_id) };
      });
      if (connection.current !== client || !scope.current.current(ticket) || selected.current !== id) return;
      displayedRun.current = runId;
      setRows(items => items.some(item => item.id === messageId) ? items : [...items, { id: messageId, runId, role: "user", content }]);
      setDraft(scope.current.draft(id)); if (acceptedMessage.current !== request.run_id) setNotice("Message saved. Connecting…");
      // Acceptance may beat the JS receipt for a newly created thread. The
      // durable run ID recovers its actual state and events without fake status.
      stage = "watch";
      if (acceptedMessage.current !== runId) await chatSendStep(stage, () => client.watch(runId));
    } catch (error) { chatSendFailure(stage, error); if (connection.current === client && scope.current.current(ticket)) { setSending(false); setError(chatFailure(error)); } }
    finally { if (sendingTicket.current === ticket) sendingTicket.current = null; }
  }
  const active = sending || isActive(run);
  const sendProblem = sendBlockReason({hasSession:connection.current !== null,ready,busy,inFlight:sending || sendingTicket.current !== null || isActive(run),blocked:blocked !== null,needsSheet,hasText:!!draft.trim()});
  // While replaying an active run, its durable prefaces belong to the single
  // live card. Once settled, the saved server messages replace that card.
  const { rows: displayedRows, hasSavedAnswer } = chatHistoryProjection(rows, run, savedRun);
  useEffect(() => { followFrame.current?.request(); }, [displayedRows, hasSavedAnswer, view.text, active]);
  return <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === "ios" ? "padding" : undefined}>
    {sheets.length > 1 ? <View style={styles.toolbar}><Text style={styles.muted}>Work on sheet</Text><ScrollView horizontal contentContainerStyle={styles.threadList}>{sheets.map(sheet => <Pressable key={sheet.id} accessibilityRole="radio" accessibilityState={{ selected: activeSheetId === sheet.id }} disabled={active} onPress={() => setSheetChoice(sheet.id)} style={[styles.thread, activeSheetId === sheet.id && styles.selected]}><Text style={styles.threadText}>{sheet.name}</Text></Pressable>)}</ScrollView></View> : null}
    {needsSheet && sheets.length === 0 ? <Text style={styles.muted}>Waiting for the design’s sheets to sync before editing.</Text> : null}
    <View style={styles.toolbar}><ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.threadList}>
      <Pressable accessibilityRole="button" onPress={() => void choose(null)} style={styles.thread}><Text style={styles.link}>New chat</Text></Pressable>
      {threads.map(t => <Pressable key={t.id} accessibilityRole="tab" accessibilityState={{ selected: thread === t.id }} onPress={() => void choose(t.id)} style={[styles.thread, thread === t.id && styles.selected]}><Text numberOfLines={1} style={styles.threadText}>{t.title || "Design conversation"}</Text></Pressable>)}
    </ScrollView></View>
    {blocked ? <View style={styles.feedback}><Text style={styles.muted}>This message was not sent. Restore it to review and send against the current design.</Text><PrimaryButton label="Restore message" onPress={() => {
      const client = connection.current;
      if (!client) return;
      const message = blocked;
      const originalThread = selected.current;
      const ticket = scope.current.ticket();
      void client.restoreBlocked(message.id).then(content => {
        if (connection.current !== client || !scope.current.current(ticket) || selected.current !== originalThread) return;
        const previous = scope.current.draft(originalThread);
        updateDraft(previous ? `${previous}\n\n${content}` : content);
        setBlocked(null); setError(null); setNotice(null);
        setRows(previous => previous.filter(row => row.id !== message.id));
      }).catch(error => { if (connection.current === client && scope.current.current(ticket)) setError(chatFailure(error)); });
    }} /></View> : null}
    <FlatList ref={list} data={displayedRows} keyExtractor={r => r.id} style={styles.history} contentContainerStyle={styles.messages} keyboardShouldPersistTaps="handled" removeClippedSubviews={false}
      onScrollBeginDrag={() => scrollIntent.current.drag()}
      onScrollEndDrag={() => { scrollIntent.current.end(); followLatest(); }}
      onMomentumScrollBegin={() => scrollIntent.current.drag()}
      onMomentumScrollEnd={() => { scrollIntent.current.end(); followLatest(); }}
      onScroll={e => { const n = e.nativeEvent; scrollIntent.current.scroll(n.contentSize.height, n.layoutMeasurement.height, n.contentOffset.y); setShowLatest(!scrollIntent.current.following); if (scrollIntent.current.needsFollow(n.contentOffset.y)) followLatest(); }} scrollEventThrottle={100}
      onLayout={event => { scrollIntent.current.layout(event.nativeEvent.layout.height); followLatest(); }}
      onContentSizeChange={(_width, height) => { scrollIntent.current.content(height); followLatest(); }}
      maintainVisibleContentPosition={showLatest ? { minIndexForVisible: 0 } : undefined}
      ListHeaderComponent={older !== null ? <Pressable accessibilityRole="button" style={styles.retry} onPress={() => { scrollIntent.current.earlier(); setShowLatest(true); const client = connection.current; const ticket = scope.current.ticket(); if (client && thread) void load(client, thread, older).catch(error => { if (connection.current === client && scope.current.current(ticket)) setError(chatFailure(error)); }); }}><Text style={styles.link}>Earlier messages</Text></Pressable> : null}
      ListEmptyComponent={busy ? <ActivityIndicator color={tokens.accent} style={styles.empty} /> : <View style={styles.empty}><Text style={styles.emptyTitle}>What would you like to build?</Text><Text style={styles.muted}>Ask about this design or describe a change. You review changes before they are applied.</Text></View>}
      renderItem={({ item }) => <View onLayout={() => followLatest()} style={[styles.bubble, chatCardLayout.assistant, item.role === "user" && styles.user, item.role === "user" && chatCardLayout.user]}><Text style={styles.author}>{item.role === "user" ? "You" : "Tokito"}</Text>{item.role === "assistant" ? <ChatMarkdown content={item.content} /> : <Text selectable style={styles.body}>{item.content}</Text>}</View>}
      ListFooterComponent={<View style={styles.footer}>{!hasSavedAnswer && (view.text || active) ? <View style={[styles.bubble, chatCardLayout.assistant]}><Text style={styles.author}>Tokito</Text>{view.text ? <ChatMarkdown content={view.text} /> : <Text style={styles.muted}>{view.activity || (sending ? "Waiting to send…" : "Working on your design…")}</Text>}{view.text && active && view.activity ? <View style={styles.activity}><ActivityIndicator size="small" color={tokens.accent} /><Text style={styles.muted}>{view.activity}</Text></View> : null}</View> : null}{view.proposals.map(proposal => <View key={proposal.turnId} style={styles.proposal}><Text style={styles.proposalTitle}>Changes prepared</Text><Text style={styles.muted}>{Array.isArray(proposal.payload.ops) ? `${proposal.payload.ops.length} proposed changes. ` : ""}Review before applying to this design.</Text><PrimaryButton label="Review changes" onPress={onReview} /></View>)}{view.error ? <View style={styles.feedback}><ErrorText>{view.error}</ErrorText></View> : null}{notice ? <Text accessibilityLiveRegion="polite" style={styles.muted}>{notice}</Text> : null}</View>}
    />
    {showLatest ? <Pressable accessibilityRole="button" style={styles.latest} onPress={() => { scrollIntent.current.latest(); setShowLatest(false); followLatest(); }}><Text style={styles.link}>Latest messages ↓</Text></Pressable> : null}
    {error ? <View accessibilityLiveRegion="polite" style={styles.feedback}><ErrorText>{error}</ErrorText><Pressable accessibilityRole="button" onPress={() => setRetry(n => n + 1)} style={styles.retry}><Text style={styles.link}>Try again</Text></Pressable></View> : null}
    {needsSheet ? <View style={styles.feedback}><Text style={styles.muted}>Choose a synchronized sheet before sending. Your draft is kept.</Text></View> : null}
    <View style={styles.composer}><TextInput accessibilityLabel="Message Tokito" value={draft} onChangeText={updateDraft} multiline maxLength={65536} placeholder="Ask about this design…" placeholderTextColor={tokens.text2} style={styles.input} />
      {isActive(run) ? <PrimaryButton label="Stop" onPress={() => { const c = connection.current; const ticket = scope.current.ticket(); if (c && run) void c.cancel(run.run_id).then(result => { if (connection.current === c && scope.current.current(ticket)) setRun(result); }).catch(error => { if (connection.current === c && scope.current.current(ticket)) setError(chatFailure(error)); }); }} /> : <PrimaryButton label="Send" disabled={sendProblem !== null} onPress={() => void send()} />}
    </View>
  </KeyboardAvoidingView>;
}
const styles = StyleSheet.create({
  footer: { gap: 12 }, activity: { flexDirection: "row", alignItems: "center", gap: 8, marginTop: 12 }, proposal: { padding: 16, gap: 12, borderWidth: 1, borderColor: tokens.borderStrong, borderRadius: tokens.radiusMd, backgroundColor: tokens.card }, proposalTitle: { color: tokens.text, fontSize: 16, fontWeight: "600" }, latest: { minHeight: 44, paddingHorizontal: 16, justifyContent: "center", alignSelf: "center", backgroundColor: tokens.bgChrome, borderRadius: tokens.radiusLg, borderWidth: 1, borderColor: tokens.border, marginVertical: 4 },
  root: { flex: 1, minHeight: 0, backgroundColor: tokens.bg }, toolbar: { borderBottomWidth: 1, borderBottomColor: tokens.border }, threadList: { padding: 8, gap: 8 }, thread: { minHeight: 44, maxWidth: 220, paddingHorizontal: 12, justifyContent: "center", borderRadius: tokens.radiusSm }, selected: { backgroundColor: tokens.card }, threadText: { color: tokens.text2, fontSize: 14 }, link: { color: tokens.accent, fontSize: 14, fontWeight: "600" }, history: { flex: 1 }, messages: { padding: 16, gap: 18, width: "100%", maxWidth: 1100, alignSelf: "center", flexGrow: 1 }, bubble: { alignSelf: "flex-start", maxWidth: "94%", padding: 16, borderRadius: tokens.radiusMd, backgroundColor: tokens.card, marginBottom: 4 }, user: { alignSelf: "flex-end", maxWidth: "85%", backgroundColor: tokens.accentSoft }, author: { fontSize: 12, color: tokens.text2, fontWeight: "600", marginBottom: 6 }, body: { color: tokens.text, fontSize: 15, lineHeight: 23 }, muted: { color: tokens.text2, fontSize: 14, lineHeight: 21 }, empty: { padding: 24, gap: 12, marginTop: 24 }, emptyTitle: { color: tokens.text, fontSize: 23, fontWeight: "600" }, feedback: { padding: 12, borderBottomWidth: 1, borderBottomColor: tokens.border }, retry: { minHeight: 44, justifyContent: "center", alignSelf: "flex-start", paddingHorizontal: 12 }, composer: { flexDirection: "row", alignItems: "flex-end", gap: 12, padding: 12, borderTopWidth: 1, borderTopColor: tokens.border, backgroundColor: tokens.bgChrome }, input: { flex: 1, minHeight: 48, maxHeight: 160, paddingHorizontal: 14, paddingVertical: 12, backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.borderStrong, borderRadius: tokens.radiusMd, color: tokens.text, fontSize: 15, lineHeight: 22, textAlignVertical: "top" },
});
