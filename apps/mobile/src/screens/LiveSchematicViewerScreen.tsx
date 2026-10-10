import type { JSX } from "react";
import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { ArrowLeft, Circuitry } from "phosphor-react-native";
import { getTokitoCompanionFfi } from "../../modules/tokito-companion-ffi/src";
import type { CloudClient } from "../native/bridge";
import { tokens } from "../theme";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";
import { chatSheets, selectedSheet } from "../chat/sheetContext";
import { parseCanonicalPreview, type CanonicalPreview } from "../viewer/canonicalPreview";
import { SnapshotViewer } from "../viewer/SnapshotViewer";

export function LiveSchematicViewerScreen({ client, token, projectId, designId, onBack }: { client: CloudClient; token: string; projectId: string; designId: string; onBack: () => void }): JSX.Element {
  const realtime = useRealtimeWorkspace();
  const resource = realtime.getResource(projectId, designId, "schematic");
  const sheets = chatSheets(resource?.payload);
  const [selected, setSelected] = useState<string | null>(null);
  const sheet = selectedSheet(sheets, selected);
  const version = resource?.version ?? 0;
  const [snapshot, setSnapshot] = useState<{ value: CanonicalPreview; scope: string } | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const base = client.baseUrl();
  const scope = `${base}\0${token}`;
  useEffect(() => {
    let cancelled = false;
    setFailed(false);
    if (sheet === null || version === 0) { setLoading(false); return; }
    setLoading(true);
    let receivedFresh = false;
    void Promise.resolve().then(() => getTokitoCompanionFfi().cachedSchematicPreview(base, token, projectId, designId, String(version), sheet))
      .then(json => { if (json !== null && !cancelled && !receivedFresh) setSnapshot({ value: parseCanonicalPreview(json, projectId, designId, sheet, version), scope }); })
      .catch(() => { /* A missing or invalid cache cannot replace authoritative data. */ });
    void Promise.resolve().then(() => getTokitoCompanionFfi().getSchematicPreview(base, token, projectId, designId, String(version), sheet))
      .then(json => parseCanonicalPreview(json, projectId, designId, sheet, version))
      .then(value => { receivedFresh = true; if (!cancelled) setSnapshot({ value, scope }); })
      .catch((error: unknown) => { if (!cancelled) { if (/Unauthorized/.test(String((error as { code?: unknown })?.code))) { receivedFresh = true; setSnapshot(null); } setFailed(true); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [base, token, projectId, designId, sheet, version, attempt, scope]);
  const current = snapshot?.scope === scope && snapshot.value.project_id === projectId && snapshot.value.design_id === designId && snapshot.value.sheet_id === sheet ? snapshot.value : null;
  const stale = current !== null && current.schematic_version !== version;
  const sheetName = sheets.find(value => value.id === sheet)?.name ?? "Choose a sheet";
  return <View style={styles.root}>
    <View style={styles.header}><Pressable accessibilityRole="button" accessibilityLabel="Back" onPress={onBack} style={styles.back}><ArrowLeft size={22} color={tokens.text} /></Pressable><View style={styles.identity}><Text style={styles.title}>Schematic</Text><Text style={styles.subtitle} numberOfLines={1}>{sheetName}</Text></View>{current ? <Text style={styles.badge}>Revision {current.schematic_version}</Text> : null}</View>
    {sheets.length > 1 ? <ScrollView horizontal style={styles.rail} contentContainerStyle={styles.tabs} showsHorizontalScrollIndicator={false}>{sheets.map(item => <Pressable key={item.id} accessibilityRole="tab" accessibilityState={{ selected: item.id === sheet }} onPress={() => setSelected(item.id)} style={[styles.tab, item.id === sheet && styles.selected]}><Text style={styles.tabText}>{item.name}</Text></Pressable>)}</ScrollView> : null}
    {current ? <View style={styles.notice}><Text style={[styles.subtitle, styles.flex]}>{stale ? loading ? "Newer edits are available. Updating this drawing…" : "Older drawing · newer edits are available" : current.cached || realtime.status !== "live" ? "Saved drawing · checking for updates" : "Matches the synchronized design"}</Text>{loading ? <ActivityIndicator color={tokens.accent} size="small" /> : null}</View> : null}
    {failed ? <View style={styles.notice}><Text style={[styles.subtitle, styles.flex]}>The current drawing could not be prepared or downloaded. Retry after the design updates or your connection returns.</Text><Pressable accessibilityRole="button" onPress={() => setAttempt(value => value + 1)} style={styles.retry}><Text style={styles.retryText}>Retry</Text></Pressable></View> : null}
    {current ? <SnapshotViewer key={`${projectId}/${designId}/${sheet}`} svg={current.svg} /> : <View style={styles.empty}>{loading ? <><ActivityIndicator color={tokens.accent} /><Text style={styles.body}>Loading the current drawing…</Text></> : <><Circuitry size={36} color={tokens.text2} /><Text style={styles.title}>{failed ? "Drawing unavailable" : sheets.length > 1 && sheet === null ? "Choose a sheet" : "No drawing available"}</Text><Text style={styles.body}>{failed ? "No unverified drawing is shown." : sheets.length > 1 && sheet === null ? "Select a sheet above to inspect its schematic." : "This design does not have a synchronized schematic yet."}</Text></>}</View>}
  </View>;
}
const styles = StyleSheet.create({ root: { flex: 1, backgroundColor: tokens.bg }, header: { minHeight: 72, padding: 12, flexDirection: "row", alignItems: "center", gap: 12, borderBottomWidth: 1, borderBottomColor: tokens.border }, back: { width: 48, height: 48, alignItems: "center", justifyContent: "center" }, identity: { flex: 1, minWidth: 0, gap: 3 }, title: { color: tokens.text, fontSize: 18, fontWeight: "600" }, subtitle: { color: tokens.text2, fontSize: 12, lineHeight: 18 }, badge: { color: tokens.text2, fontSize: 12, backgroundColor: tokens.card, padding: 8, borderRadius: 8 }, body: { color: tokens.text2, fontSize: 14, lineHeight: 21, textAlign: "center" }, empty: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24, gap: 16 }, rail: { flexGrow: 0, maxHeight: 64 }, tabs: { padding: 10, gap: 8 }, tab: { minHeight: 44, justifyContent: "center", paddingHorizontal: 16, borderRadius: 10, borderWidth: 1, borderColor: tokens.border, backgroundColor: tokens.card }, selected: { borderColor: tokens.accent, backgroundColor: tokens.accentSoft }, tabText: { color: tokens.text, fontSize: 13 }, notice: { paddingHorizontal: 16, paddingVertical: 8, flexDirection: "row", alignItems: "center", gap: 10, borderBottomWidth: 1, borderBottomColor: tokens.border }, flex: { flex: 1 }, retry: { minHeight: 44, paddingHorizontal: 16, justifyContent: "center", borderRadius: 10, backgroundColor: tokens.card }, retryText: { color: tokens.accent, fontWeight: "600" } });
