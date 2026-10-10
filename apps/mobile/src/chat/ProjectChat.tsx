import type { JSX } from "react";
import { useEffect, useRef, useState } from "react";
import { ActivityIndicator, FlatList, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { getTokitoCompanionFfi } from "../../modules/tokito-companion-ffi/src";
import { ConversationClient } from "./conversationClient";
import { chatFailure, ConversationScope } from "./conversationState";
import { emptyRun, isActive, object, parseRun, reduceRun, string, type ChatRow, type ChatRun, type ChatThread, type RunView } from "./conversationModel";
import { tokens } from "../theme";
import { ErrorText, PrimaryButton } from "../ui/primitives";
import { selectedSheet, type ChatSheet } from "./sheetContext";

type Props = { baseUrl: string; token: string; project: string; design: string; version: number; sheets: ChatSheet[] };
export function ProjectChat({ baseUrl, token, project, design, version, sheets }: Props): JSX.Element {
  const [sheetChoice, setSheetChoice] = useState<string | null>(null);
  const activeSheetId = selectedSheet(sheets, sheetChoice);
  const needsSheet = version > 0 && activeSheetId === null;
  const connection = useRef<ConversationClient | null>(null);
  const selected = useRef<string | null>(null);
  const list = useRef<FlatList<ChatRow>>(null);
  const atBottom = useRef(true);
  const [threads, setThreads] = useState<ChatThread[]>([]);
  const [thread, setThread] = useState<string | null>(null);
  const [rows, setRows] = useState<ChatRow[]>([]);
  const [older, setOlder] = useState<number | null>(null);
  const [run, setRun] = useState<ChatRun | null>(null);
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

  async function load(client: ConversationClient, id: string, before: number | null = null): Promise<void> {
    const ticket = scope.current.ticket();
    const request = ++historyRequest.current;
    const page = await client.history(project, design, id, before);
    if (connection.current !== client || selected.current !== id || !scope.current.current(ticket) || request !== historyRequest.current) return;
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
      setRun(page.latestRun);
      setView(emptyRun());
      if (page.latestRun !== null) await client.watch(page.latestRun.run_id);
    }
  }

  useEffect(() => {
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
    setThread(null); setRows([]); setRun(null); setView(emptyRun()); setBusy(true); setError(null);
    const current = () => epoch.current === id && connection.current === client;
    void client.open(baseUrl, token, change => {
      if (!current()) return;
      if (change.type === "accepted") {
        const accepted = parseRun(change.run);
        if (accepted.thread_id !== selected.current) return;
        acceptedMessage.current = accepted.run_id;
        historyRequest.current++;
        setRun(accepted); setNotice(null); setSending(false);
        setView(emptyRun());
        const ticket = scope.current.ticket();
        void client.watch(accepted.run_id).catch(error => { if (current() && scope.current.current(ticket)) setError(chatFailure(error)); });
      } else if (change.type === "event") {
        setView(previous => {
          try { return reduceRun(previous, change.event); }
          catch { return { ...previous, error: "Live response could not be displayed. Reopen this conversation to recover it." }; }
        });
      } else if (change.type === "settled") {
        const settled = parseRun(change.run);
        if (settled.thread_id !== selected.current) return;
        setRun(settled); setSending(false); setNotice(null);
        const ticket = scope.current.ticket();
        const request = ++historyRequest.current;
        void client.history(project, design, settled.thread_id).then(page => {
          if (current() && scope.current.current(ticket) && request === historyRequest.current && selected.current === settled.thread_id) {
            setRows(prior => [...prior.filter(row => !page.messages.some(message => message.id === row.id)), ...page.messages]);
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
    }).then(async () => {
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
  }, [baseUrl, token, project, design, retry]);

  async function choose(id: string | null): Promise<void> {
    const client = connection.current;
    if (!client) return;
    const ticket = scope.current.next();
    sendingTicket.current = null;
    setDraft(scope.current.draft(id));
    selected.current = id; acceptedMessage.current = null; setThread(id); setRows([]); setOlder(null); setView(emptyRun()); setRun(null); setError(null); setNotice(null); setBlocked(null); setSending(false); setBusy(true);
    try { await client.stopWatching(); if (scope.current.current(ticket) && id !== null) await load(client, id); }
    catch (error) { if (connection.current === client && scope.current.current(ticket)) setError(chatFailure(error)); }
    finally { if (connection.current === client && scope.current.current(ticket)) setBusy(false); }
  }
  async function send(): Promise<void> {
    if (needsSheet) { setError("Choose the sheet you want Tokito to work on before sending."); return; }
    const client = connection.current;
    const content = draft.trim();
    if (!client || !ready || !content || sending || sendingTicket.current !== null || blocked || isActive(run)) return;
    const ticket = scope.current.ticket();
    sendingTicket.current = ticket;
    setSending(true); setError(null);
    try {
      let id = selected.current;
      if (id === null) {
        const created = await client.create(project, design, content.slice(0, 80));
        if (connection.current !== client || !scope.current.current(ticket) || selected.current !== null) return;
        id = created.id; selected.current = id; setThread(id); setThreads(items => [created, ...items]);
        scope.current.save(null, ""); scope.current.save(id, content);
      }
      const pending = await client.sendChat(project, design, id, content, version, activeSheetId);
      // A successful durable enqueue owns this text, even if navigation finished
      // while it was pending. Do not leave a second sendable copy in its draft.
      if (scopeKey.current === JSON.stringify([baseUrl, token, project, design]) && scope.current.draft(id).trim() === content) scope.current.save(id, "");
      if (connection.current !== client || !scope.current.current(ticket) || selected.current !== id) return;
      const request = object(pending.request);
      setRows(items => items.some(item => item.id === request.message_id) ? items : [...items, { id: string(request.message_id), runId: string(request.run_id), role: "user", content }]);
      setDraft(scope.current.draft(id)); if (acceptedMessage.current !== request.run_id) setNotice("Message saved. Connecting…"); atBottom.current = true;
    } catch (error) { if (connection.current === client && scope.current.current(ticket)) { setSending(false); setError(chatFailure(error)); } }
    finally { if (sendingTicket.current === ticket) sendingTicket.current = null; }
  }
  const active = sending || isActive(run);
  const hasSavedAnswer = run !== null && rows.some(r => r.role === "assistant" && r.runId === run.run_id);
  return <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === "ios" ? "padding" : undefined}>
    {sheets.length > 1 ? <View style={styles.toolbar}><Text style={styles.muted}>Work on sheet</Text><ScrollView horizontal contentContainerStyle={styles.threadList}>{sheets.map(sheet => <Pressable key={sheet.id} accessibilityRole="radio" accessibilityState={{ selected: activeSheetId === sheet.id }} disabled={active} onPress={() => setSheetChoice(sheet.id)} style={[styles.thread, activeSheetId === sheet.id && styles.selected]}><Text style={styles.threadText}>{sheet.name}</Text></Pressable>)}</ScrollView></View> : null}
    {needsSheet && sheets.length === 0 ? <Text style={styles.muted}>Waiting for the design’s sheets to sync before editing.</Text> : null}
    <View style={styles.toolbar}><ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.threadList}>
      <Pressable accessibilityRole="button" onPress={() => void choose(null)} style={styles.thread}><Text style={styles.link}>New chat</Text></Pressable>
      {threads.map(t => <Pressable key={t.id} accessibilityRole="tab" accessibilityState={{ selected: thread === t.id }} onPress={() => void choose(t.id)} style={[styles.thread, thread === t.id && styles.selected]}><Text numberOfLines={1} style={styles.threadText}>{t.title || "Design conversation"}</Text></Pressable>)}
    </ScrollView></View>
    {error ? <View style={styles.feedback}><ErrorText>{error}</ErrorText><Pressable accessibilityRole="button" onPress={() => setRetry(n => n + 1)} style={styles.retry}><Text style={styles.link}>Try again</Text></Pressable></View> : null}
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
    <FlatList ref={list} data={rows} keyExtractor={r => r.id} style={styles.history} contentContainerStyle={styles.messages} keyboardShouldPersistTaps="handled" removeClippedSubviews={false}
      onScroll={e => { const n = e.nativeEvent; atBottom.current = n.contentSize.height - n.layoutMeasurement.height - n.contentOffset.y < 100; }} scrollEventThrottle={100}
      onContentSizeChange={() => { if (atBottom.current) list.current?.scrollToEnd({ animated: false }); }}
      ListHeaderComponent={older !== null ? <Pressable accessibilityRole="button" style={styles.retry} onPress={() => { const client = connection.current; const ticket = scope.current.ticket(); if (client && thread) void load(client, thread, older).catch(error => { if (connection.current === client && scope.current.current(ticket)) setError(chatFailure(error)); }); }}><Text style={styles.link}>Earlier messages</Text></Pressable> : null}
      ListEmptyComponent={busy ? <ActivityIndicator color={tokens.accent} style={styles.empty} /> : <View style={styles.empty}><Text style={styles.emptyTitle}>What would you like to build?</Text><Text style={styles.muted}>Ask about this design or describe a change. You review changes before they are applied.</Text></View>}
      renderItem={({ item }) => <View style={[styles.bubble, item.role === "user" && styles.user]}><Text style={styles.author}>{item.role === "user" ? "You" : "Tokito"}</Text><Text selectable style={styles.body}>{item.content}</Text></View>}
      ListFooterComponent={<View>{!hasSavedAnswer && (view.text || active) ? <View style={styles.bubble}><Text style={styles.author}>Tokito</Text>{view.text ? <Text selectable style={styles.body}>{view.text}</Text> : <Text style={styles.muted}>{view.activity || "Working on your design…"}</Text>}</View> : null}{view.error ? <ErrorText>{view.error}</ErrorText> : null}{notice ? <Text accessibilityLiveRegion="polite" style={styles.muted}>{notice}</Text> : null}</View>}
    />
    <View style={styles.composer}><TextInput accessibilityLabel="Message Tokito" value={draft} onChangeText={updateDraft} multiline maxLength={65536} placeholder="Ask about this design…" placeholderTextColor={tokens.text2} style={styles.input} />
      {isActive(run) ? <PrimaryButton label="Stop" onPress={() => { const c = connection.current; const ticket = scope.current.ticket(); if (c && run) void c.cancel(run.run_id).then(result => { if (connection.current === c && scope.current.current(ticket)) setRun(result); }).catch(error => { if (connection.current === c && scope.current.current(ticket)) setError(chatFailure(error)); }); }} /> : <PrimaryButton label="Send" disabled={!ready || busy || sending || blocked !== null || !draft.trim()} onPress={() => void send()} />}
    </View>
  </KeyboardAvoidingView>;
}
const styles = StyleSheet.create({
  root: { flex: 1, minHeight: 0, backgroundColor: tokens.bg }, toolbar: { borderBottomWidth: 1, borderBottomColor: tokens.border }, threadList: { padding: 8, gap: 8 }, thread: { minHeight: 44, maxWidth: 220, paddingHorizontal: 12, justifyContent: "center", borderRadius: tokens.radiusSm }, selected: { backgroundColor: tokens.card }, threadText: { color: tokens.text2, fontSize: 14 }, link: { color: tokens.accent, fontSize: 14, fontWeight: "600" }, history: { flex: 1 }, messages: { padding: 16, gap: 18, width: "100%", maxWidth: 1100, alignSelf: "center", flexGrow: 1 }, bubble: { alignSelf: "flex-start", maxWidth: "94%", padding: 16, borderRadius: tokens.radiusMd, backgroundColor: tokens.card, marginBottom: 4 }, user: { alignSelf: "flex-end", maxWidth: "85%", backgroundColor: tokens.accentSoft }, author: { fontSize: 12, color: tokens.text2, fontWeight: "600", marginBottom: 6 }, body: { color: tokens.text, fontSize: 15, lineHeight: 23 }, muted: { color: tokens.text2, fontSize: 14, lineHeight: 21 }, empty: { padding: 24, gap: 12, marginTop: 24 }, emptyTitle: { color: tokens.text, fontSize: 23, fontWeight: "600" }, feedback: { padding: 12, borderBottomWidth: 1, borderBottomColor: tokens.border }, retry: { minHeight: 44, justifyContent: "center", alignSelf: "flex-start", paddingHorizontal: 12 }, composer: { flexDirection: "row", alignItems: "flex-end", gap: 12, padding: 12, borderTopWidth: 1, borderTopColor: tokens.border, backgroundColor: tokens.bgChrome }, input: { flex: 1, minHeight: 48, maxHeight: 160, paddingHorizontal: 14, paddingVertical: 12, backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.borderStrong, borderRadius: tokens.radiusMd, color: tokens.text, fontSize: 15, lineHeight: 22, textAlignVertical: "top" },
});
