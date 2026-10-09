import type { JSX } from "react";
import { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { ArrowLeft, Circuitry } from "phosphor-react-native";
import type { ProjectDetail } from "../api/cloudHttp";
import type { CloudClient } from "../native/bridge";
import { tokens } from "../theme";
import { schematicSnapshots } from "../viewer/snapshotRuntime";
import { SchematicViewerScreen } from "./SchematicViewerScreen";

// A synchronized document is not a rendered snapshot. Do not approximate its
// symbols or advertise old published artifacts as a live revision.
export function LiveSchematicViewerScreen({ client, token, projectId, designId, onBack }: { client: CloudClient; token: string; projectId: string; designId: string; onBack: () => void }): JSX.Element {
  const [project, setProject] = useState<ProjectDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setProject(null); setFailed(false); setSelected(null);
    void client.getProject(token, projectId).then((value) => { if (!cancelled) setProject(value); }).catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [client, token, projectId, designId, attempt]);
  const artifacts = project?.id === projectId ? schematicSnapshots(project, designId) : [];
  const artifact = artifacts.find((item) => item.name === selected) ?? (artifacts.length === 1 ? artifacts[0] : undefined);
  if (artifact && project) return <SchematicViewerScreen client={client} token={token} projectId={projectId} artifactName={artifact.name} publishedAt={project.updated_at} onBack={artifacts.length > 1 ? () => setSelected(null) : onBack} />;
  return <View style={styles.root}><View style={styles.header}><Pressable accessibilityRole="button" accessibilityLabel="Back" onPress={onBack} style={styles.back}><ArrowLeft size={22} color={tokens.text} /></Pressable><Text style={styles.title}>Schematic snapshots</Text></View>
    {project === null && !failed ? <View style={styles.empty}><ActivityIndicator color={tokens.accent} /><Text style={styles.body}>Finding published drawings…</Text></View> : artifacts.length > 0 ? <ScrollView contentContainerStyle={styles.list}><Text style={styles.body}>Choose a published drawing. Newer edits may not be included.</Text>{artifacts.map((item) => <Pressable key={item.name} accessibilityRole="button" onPress={() => setSelected(item.name)} style={styles.item}><Circuitry size={24} color={tokens.accent} /><Text style={styles.name}>{item.name}</Text><Text style={styles.open}>Open</Text></Pressable>)}</ScrollView> : <View style={styles.empty}><Circuitry size={36} color={tokens.text2} /><Text style={styles.title}>{failed ? "Could not load snapshots" : "No snapshot for this design"}</Text><Text style={styles.body}>{failed ? "Check your connection, then try again." : "Publish a schematic drawing from the desktop, then refresh here."}</Text><Pressable accessibilityRole="button" onPress={() => setAttempt((value) => value + 1)} style={styles.retry}><Text style={styles.retryText}>Refresh</Text></Pressable></View>}
  </View>;
}
const styles = StyleSheet.create({ root: { flex: 1, backgroundColor: tokens.bg }, header: { minHeight: 72, padding: 12, flexDirection: "row", alignItems: "center", gap: 12, borderBottomWidth: 1, borderBottomColor: tokens.border }, back: { width: 48, height: 48, alignItems: "center", justifyContent: "center" }, title: { color: tokens.text, fontSize: 18, fontWeight: "600" }, body: { color: tokens.text2, fontSize: 14, lineHeight: 21, textAlign: "center" }, empty: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24, gap: 16 }, list: { padding: 16, gap: 12 }, item: { minHeight: 72, padding: 16, borderRadius: 12, backgroundColor: tokens.card, flexDirection: "row", alignItems: "center", gap: 12 }, name: { flex: 1, color: tokens.text, fontSize: 14 }, open: { color: tokens.accent, fontWeight: "600" }, retry: { minHeight: 48, paddingHorizontal: 22, borderRadius: 12, backgroundColor: tokens.card, justifyContent: "center" }, retryText: { color: tokens.accent, fontWeight: "600" } });
