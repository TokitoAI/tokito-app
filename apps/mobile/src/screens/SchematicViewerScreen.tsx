import type { JSX } from "react";
import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { ArrowLeft } from "phosphor-react-native";
import type { CloudClient } from "../native/bridge";
import { tokens } from "../theme";
import { SnapshotViewer } from "../viewer/SnapshotViewer";

export interface SchematicViewerScreenProps {
  client: CloudClient; token: string; projectId: string; artifactName: string; onBack: () => void;
  publishedAt?: string;
}
export function SchematicViewerScreen({ client, token, projectId, artifactName, onBack, publishedAt }: SchematicViewerScreenProps): JSX.Element {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<{ key: string; svg?: string; error?: string } | null>(null);
  const key = `${projectId}/${artifactName}/${publishedAt ?? ""}`;
  useEffect(() => {
    let cancelled = false;
    setState(null);
    void client.getArtifactText(token, projectId, artifactName).then((artifact) => {
      if (artifact.contentType.split(";")[0].trim() !== "image/svg+xml") throw new Error("Unsupported drawing format");
      if (!cancelled) setState({ key, svg: artifact.body });
    }).catch(() => { if (!cancelled) setState({ key, error: "The drawing could not be downloaded. Check your connection and try again." }); });
    return () => { cancelled = true; };
  }, [client, token, projectId, artifactName, key, attempt]);
  const current = state?.key === key ? state : null;
  return <View style={styles.root}>
    <View style={styles.header}><Pressable accessibilityRole="button" accessibilityLabel="Back" onPress={onBack} style={styles.back}><ArrowLeft size={22} color={tokens.text} /></Pressable><View style={styles.identity}><Text style={styles.title}>Schematic</Text><Text numberOfLines={1} style={styles.subtitle}>{artifactName}</Text></View><Text style={styles.badge}>Snapshot</Text></View>
    <View style={styles.notice}><Text style={styles.noticeText}>Published drawing{publishedAt && Number.isFinite(Date.parse(publishedAt)) ? ` · ${new Date(publishedAt).toLocaleDateString()}` : ""}. Newer edits may not be included.</Text></View>
    {current?.svg !== undefined ? <SnapshotViewer key={`${key}/${attempt}`} svg={current.svg} /> : <View style={styles.state}>{current?.error ? <><Text style={styles.error}>{current.error}</Text><Pressable accessibilityRole="button" onPress={() => setAttempt((value) => value + 1)} style={styles.retry}><Text style={styles.retryText}>Try again</Text></Pressable></> : <><ActivityIndicator color={tokens.accent} /><Text style={styles.subtitle}>Loading snapshot…</Text></>}</View>}
  </View>;
}
const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg }, header: { minHeight: 72, paddingHorizontal: 12, paddingVertical: 10, flexDirection: "row", alignItems: "center", gap: 12, backgroundColor: tokens.bgChrome, borderBottomWidth: 1, borderBottomColor: tokens.border }, back: { width: 48, height: 48, alignItems: "center", justifyContent: "center", borderRadius: 12 }, identity: { flex: 1, minWidth: 0, gap: 3 }, title: { color: tokens.text, fontSize: 18, fontWeight: "600" }, subtitle: { color: tokens.text2, fontSize: 12 }, badge: { color: tokens.text2, fontSize: 12, paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8, backgroundColor: tokens.card }, notice: { paddingHorizontal: 16, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: tokens.border }, noticeText: { color: tokens.text2, fontSize: 12, lineHeight: 17 }, state: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24, gap: 16 }, error: { color: tokens.text2, textAlign: "center", fontSize: 14, lineHeight: 21 }, retry: { minHeight: 48, paddingHorizontal: 22, justifyContent: "center", borderRadius: 12, backgroundColor: tokens.accent }, retryText: { color: tokens.accentInk, fontWeight: "600" },
});
