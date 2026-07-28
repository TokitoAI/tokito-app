import type { JSX } from "react";
import { useCallback, useEffect, useState } from "react";
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import type {
  BomLine,
  HttpCloudClient,
  ProjectDetail,
  Proposal,
} from "../api/cloudHttp.js";
import { colors } from "../theme.js";

export interface ProjectDetailScreenProps {
  client: HttpCloudClient;
  token: string;
  projectId: string;
  onBack: () => void;
  onOpenSvg: (artifactName: string) => void;
}

export function ProjectDetailScreen({
  client,
  token,
  projectId,
  onBack,
  onOpenSvg,
}: ProjectDetailScreenProps): JSX.Element {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [proposals, setProposals] = useState<Proposal[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [project, pending] = await Promise.all([
        client.getProject(token, projectId),
        client.listProposals(token, projectId, "pending"),
      ]);
      setDetail(project);
      setProposals(pending);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load project");
    } finally {
      setLoading(false);
    }
  }, [client, token, projectId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function decide(id: string, decision: "approve" | "reject"): Promise<void> {
    setBusyId(id);
    setError(null);
    try {
      await client.decideProposal(token, projectId, id, decision);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Decision failed");
    } finally {
      setBusyId(null);
    }
  }

  const bomLines: BomLine[] =
    detail?.designs.flatMap((d) =>
      d.bom_lines.map((line) => ({
        ...line,
        ref: `${d.name}:${line.ref}`,
      })),
    ) ?? [];

  const svgArtifacts =
    detail?.artifact_manifest.filter((a) => a.name.endsWith(".svg")) ?? [];

  return (
    <View style={styles.root}>
      <Pressable onPress={onBack}>
        <Text style={styles.link}>← Projects</Text>
      </Pressable>
      <Text style={styles.title}>{detail?.name ?? "Project"}</Text>
      {loading ? <ActivityIndicator color={colors.accent} /> : null}
      {error !== null ? <Text style={styles.error}>{error}</Text> : null}

      <Text style={styles.h2}>BOM</Text>
      {bomLines.length === 0 && !loading ? (
        <Text style={styles.muted}>No BOM lines in snapshot.</Text>
      ) : null}
      <FlatList
        data={bomLines}
        keyExtractor={(item, i) => `${item.ref}-${i}`}
        style={{ maxHeight: 180 }}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <Text style={styles.rowRef}>{item.ref}</Text>
            <Text style={styles.rowVal}>
              {item.value} ×{item.qty}
            </Text>
          </View>
        )}
      />

      <Text style={styles.h2}>Schematics</Text>
      {svgArtifacts.map((a) => (
        <Pressable key={a.name} style={styles.card} onPress={() => onOpenSvg(a.name)}>
          <Text style={styles.cardTitle}>{a.name}</Text>
          <Text style={styles.muted}>{a.bytes} bytes</Text>
        </Pressable>
      ))}
      {svgArtifacts.length === 0 && !loading ? (
        <Text style={styles.muted}>No SVG artifacts yet.</Text>
      ) : null}

      <Text style={styles.h2}>Pending proposals</Text>
      {proposals.length === 0 && !loading ? (
        <Text style={styles.muted}>Nothing pending.</Text>
      ) : null}
      {proposals.map((p) => (
        <View key={p.id} style={styles.card}>
          <Text style={styles.cardTitle}>{p.title}</Text>
          <Text style={styles.muted}>{p.summary}</Text>
          <View style={styles.actions}>
            <Pressable
              style={styles.btn}
              disabled={busyId === p.id}
              onPress={() => void decide(p.id, "approve")}
            >
              <Text style={styles.btnText}>Approve</Text>
            </Pressable>
            <Pressable
              style={[styles.btn, styles.btnDanger]}
              disabled={busyId === p.id}
              onPress={() => void decide(p.id, "reject")}
            >
              <Text style={styles.btnText}>Reject</Text>
            </Pressable>
          </View>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg, paddingHorizontal: 20, paddingTop: 56, gap: 8 },
  link: { color: colors.accent, marginBottom: 8 },
  title: { color: colors.text, fontSize: 24, fontWeight: "700", marginBottom: 8 },
  h2: { color: colors.text, fontSize: 16, fontWeight: "600", marginTop: 12 },
  muted: { color: colors.textMuted, fontSize: 13 },
  error: { color: colors.danger },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    paddingVertical: 6,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  rowRef: { color: colors.textMuted, width: "40%" },
  rowVal: { color: colors.text, flex: 1, textAlign: "right" },
  card: {
    backgroundColor: colors.surface,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: 12,
    padding: 12,
    marginTop: 8,
  },
  cardTitle: { color: colors.text, fontWeight: "600" },
  actions: { flexDirection: "row", gap: 8, marginTop: 10 },
  btn: {
    backgroundColor: colors.accent,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  btnDanger: { backgroundColor: colors.danger },
  btnText: { color: colors.text, fontWeight: "600" },
});
