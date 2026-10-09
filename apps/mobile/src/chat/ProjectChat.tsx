import type { JSX } from "react";
import { useEffect, useRef, useState } from "react";
import { ActivityIndicator, FlatList, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { getTokitoCompanionFfi } from "../../modules/tokito-companion-ffi/src";
import { ConversationClient } from "./conversationClient";
import { emptyRun, isActive, object, parseRun, reduceRun, string, type ChatRow, type ChatRun, type ChatThread, type RunView } from "./conversationModel";
import { tokens } from "../theme";
import { ErrorText, PrimaryButton } from "../ui/primitives";

type Props = { baseUrl: string; token: string; project: string; design: string; version: number };
export function ProjectChat({ baseUrl, token, project, design, version }: Props): JSX.Element {
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

  async function load(client: ConversationClient, id: string, before: number | null = null): Promise<void> {
    const page = await client.history(project, design, id, before);
    if (connection.current !== client || selected.current !== id) return;
    setOlder(page.olderCursor);
    setRows(prior => before === null ? page.messages : [...page.messages, ...prior.filter(r => !page.messages.some(m => m.id === r.id))]);
    if (before === null) {
      const [pending, recovered] = await Promise.all([client.queued(id), client.recoveredDraft(id)]);
      if (connection.current !== client || selected.current !== id) return;
      setBlocked(pending?.blocked ? pending : null);
      setSending(pending !== null && !pending.blocked);
      if (pending) {
        setRows(prior => prior.some(row => row.id === pending.id) ? prior : [...prior, { id: pending.id, runId: pending.runId, role: "user", content: pending.content }]);
        setNotice(pending.blocked ? null : "Message saved. Waiting for a connection…");
      }
      if (recovered !== null) setDraft(previous => previous || recovered);
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
    selected.current = null;
    setThread(null); setRows([]); setRun(null); setView(emptyRun()); setBusy(true); setError(null);
    const current = () => epoch.current === id && connection.current === client;
    void client.open(baseUrl, token, change => {
      if (!current()) return;
      if (change.type === "accepted") {
        const accepted = parseRun(change.run);
        if (accepted.thread_id !== selected.current) return;
        acceptedMessage.current = accepted.run_id;
        setRun(accepted); setNotice(null); setSending(false);
        setView(emptyRun());
        void client.watch(accepted.run_id).catch(() => setError("Unable to reconnect to the response. Reopen this conversation."));
      } else if (change.type === "event") {
        setView(previous => {
          try { return reduceRun(previous, change.event); }
          catch { return { ...previous, error: "Live response could not be displayed. Reopen this conversation to recover it." }; }
        });
      } else if (change.type === "settled") {
        const settled = parseRun(change.run);
        if (settled.thread_id !== selected.current) return;
        setRun(settled); setSending(false); setNotice(null);
        void client.history(project, design, settled.thread_id).then(page => {
          if (current() && selected.current === settled.thread_id) { setRows(page.messages); setOlder(page.olderCursor); }
        }).catch(() => { if (current()) setError("The run finished, but saved history could not be refreshed. Reopen this conversation."); });
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
      const first = items[0];
      if (first) { selected.current = first.id; setThread(first.id); await load(client, first.id); }
    }).catch(() => { if (current()) setError("Chat could not connect. Check your connection and try again."); })
      .finally(() => { if (current()) setBusy(false); });
    return () => { epoch.current++; connection.current = null; void client.close().catch(() => {}); };
    // The session owns its callbacks for this exact account/project scope.
  }, [baseUrl, token, project, design, retry]);

  async function choose(id: string | null): Promise<void> {
    const client = connection.current;
    if (!client) return;
    selected.current = id; acceptedMessage.current = null; setThread(id); setRows([]); setOlder(null); setView(emptyRun()); setRun(null); setError(null); setNotice(null); setBlocked(null); setSending(false); setBusy(true);
    try { await client.stopWatching(); if (id !== null) await load(client, id); }
    catch { if (connection.current === client && selected.current === id) setError("Conversation could not be loaded. Try opening it again."); }
    finally { if (connection.current === client && selected.current === id) setBusy(false); }
  }
  async function send(): Promise<void> {
    const client = connection.current;
    const content = draft.trim();
    if (!client || !content || sending || blocked || isActive(run)) return;
    setSending(true); setError(null);
    try {
      let id = selected.current;
      if (id === null) {
        const created = await client.create(project, design, content.slice(0, 80));
        if (connection.current !== client || selected.current !== null) return;
        id = created.id; selected.current = id; setThread(id); setThreads(items => [created, ...items]);
      }
      const pending = await client.send(project, design, id, content, version, version === 0 ? "planner" : "schematic");
      if (connection.current !== client || selected.current !== id) return;
      const request = object(pending.request);
      setRows(items => [...items, { id: string(request.message_id), runId: string(request.run_id), role: "user", content }]);
      setDraft(""); if (acceptedMessage.current !== request.run_id) setNotice("Message saved. Connecting…"); atBottom.current = true;
    } catch { setSending(false); setError("Message could not be queued. Your draft is still here; try again."); }
  }
  const active = sending || isActive(run);
  const hasSavedAnswer = run !== null && rows.some(r => r.role === "assistant" && r.runId === run.run_id);
  return <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === "ios" ? "padding" : undefined}>
    <View style={styles.toolbar}><ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.threadList}>
      <Pressable accessibilityRole="button" onPress={() => void choose(null)} style={styles.thread}><Text style={styles.link}>New chat</Text></Pressable>
      {threads.map(t => <Pressable key={t.id} accessibilityRole="tab" accessibilityState={{ selected: thread === t.id }} onPress={() => void choose(t.id)} style={[styles.thread, thread === t.id && styles.selected]}><Text numberOfLines={1} style={styles.threadText}>{t.title || "Design conversation"}</Text></Pressable>)}
    </ScrollView></View>
    {error ? <View style={styles.feedback}><ErrorText>{error}</ErrorText><Pressable accessibilityRole="button" onPress={() => setRetry(n => n + 1)} style={styles.retry}><Text style={styles.link}>Reconnect</Text></Pressable></View> : null}
    {blocked ? <View style={styles.feedback}><Text style={styles.muted}>This message was not sent. Restore it to review and send against the current design.</Text><PrimaryButton label="Restore message" onPress={() => {
      const client = connection.current;
      if (!client) return;
      const message = blocked;
      const originalThread = selected.current;
      void client.restoreBlocked(message.id).then(content => {
        if (connection.current !== client || selected.current !== originalThread) return;
        setDraft(previous => previous ? `${previous}\n\n${content}` : content);
        setBlocked(null); setError(null); setNotice(null);
        setRows(previous => previous.filter(row => row.id !== message.id));
      }).catch(() => setError("The message could not be restored. Reopen this conversation."));
    }} /></View> : null}
    <FlatList ref={list} data={rows} keyExtractor={r => r.id} style={styles.history} contentContainerStyle={styles.messages} keyboardShouldPersistTaps="handled" removeClippedSubviews={false}
      onScroll={e => { const n = e.nativeEvent; atBottom.current = n.contentSize.height - n.layoutMeasurement.height - n.contentOffset.y < 100; }} scrollEventThrottle={100}
      onContentSizeChange={() => { if (atBottom.current) list.current?.scrollToEnd({ animated: false }); }}
      ListHeaderComponent={older !== null ? <Pressable accessibilityRole="button" style={styles.retry} onPress={() => { if (connection.current && thread) void load(connection.current, thread, older).catch(() => setError("Earlier messages could not be loaded.")); }}><Text style={styles.link}>Earlier messages</Text></Pressable> : null}
      ListEmptyComponent={busy ? <ActivityIndicator color={tokens.accent} style={styles.empty} /> : <View style={styles.empty}><Text style={styles.emptyTitle}>What would you like to build?</Text><Text style={styles.muted}>Ask about this design or describe a change. You review changes before they are applied.</Text></View>}
      renderItem={({ item }) => <View style={[styles.bubble, item.role === "user" && styles.user]}><Text style={styles.author}>{item.role === "user" ? "You" : "Tokito"}</Text><Text selectable style={styles.body}>{item.content}</Text></View>}
      ListFooterComponent={<View>{!hasSavedAnswer && (view.text || active) ? <View style={styles.bubble}><Text style={styles.author}>Tokito</Text>{view.text ? <Text selectable style={styles.body}>{view.text}</Text> : <Text style={styles.muted}>{view.activity || "Working on your design…"}</Text>}</View> : null}{view.error ? <ErrorText>{view.error}</ErrorText> : null}{notice ? <Text accessibilityLiveRegion="polite" style={styles.muted}>{notice}</Text> : null}</View>}
    />
    <View style={styles.composer}><TextInput accessibilityLabel="Message Tokito" value={draft} onChangeText={setDraft} multiline maxLength={65536} placeholder="Ask about this design…" placeholderTextColor={tokens.text2} style={styles.input} />
      {isActive(run) ? <PrimaryButton label="Stop" onPress={() => { const c = connection.current; if (c && run) void c.cancel(run.run_id).then(setRun).catch(() => setError("Could not request cancellation. Try again.")); }} /> : <PrimaryButton label="Send" disabled={busy || sending || blocked !== null || !draft.trim()} onPress={() => void send()} />}
    </View>
  </KeyboardAvoidingView>;
}
const styles = StyleSheet.create({
  root: { flex: 1, minHeight: 0, backgroundColor: tokens.bg }, toolbar: { borderBottomWidth: 1, borderBottomColor: tokens.border }, threadList: { padding: 8, gap: 8 }, thread: { minHeight: 44, maxWidth: 220, paddingHorizontal: 12, justifyContent: "center", borderRadius: tokens.radiusSm }, selected: { backgroundColor: tokens.card }, threadText: { color: tokens.text2, fontSize: 14 }, link: { color: tokens.accent, fontSize: 14, fontWeight: "600" }, history: { flex: 1 }, messages: { padding: 16, gap: 18, width: "100%", maxWidth: 1100, alignSelf: "center", flexGrow: 1 }, bubble: { alignSelf: "flex-start", maxWidth: "94%", padding: 16, borderRadius: tokens.radiusMd, backgroundColor: tokens.card, marginBottom: 4 }, user: { alignSelf: "flex-end", maxWidth: "85%", backgroundColor: tokens.accentSoft }, author: { fontSize: 12, color: tokens.text2, fontWeight: "600", marginBottom: 6 }, body: { color: tokens.text, fontSize: 15, lineHeight: 23 }, muted: { color: tokens.text2, fontSize: 14, lineHeight: 21 }, empty: { padding: 24, gap: 12, marginTop: 24 }, emptyTitle: { color: tokens.text, fontSize: 23, fontWeight: "600" }, feedback: { padding: 12, borderBottomWidth: 1, borderBottomColor: tokens.border }, retry: { minHeight: 44, justifyContent: "center", alignSelf: "flex-start", paddingHorizontal: 12 }, composer: { flexDirection: "row", alignItems: "flex-end", gap: 12, padding: 12, borderTopWidth: 1, borderTopColor: tokens.border, backgroundColor: tokens.bgChrome }, input: { flex: 1, minHeight: 48, maxHeight: 160, paddingHorizontal: 14, paddingVertical: 12, backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.borderStrong, borderRadius: tokens.radiusMd, color: tokens.text, fontSize: 15, lineHeight: 22, textAlignVertical: "top" },
});
