import type { JSX } from "react";
import { useCallback, useEffect, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import {
  ArrowLeft,
  Check,
  Circuitry,
  ListBullets,
  Sparkle,
  X,
} from "phosphor-react-native";
import type {
  BomLine,
  HttpCloudClient,
  ProjectDetail,
  Proposal,
} from "../api/cloudHttp.js";
import { CompanionError } from "../native/types.js";
import { tokens } from "../theme.js";
import {
  Card,
  ErrorText,
  Muted,
  PrimaryButton,
  SectionLabel,
} from "../ui/primitives.js";

export interface ProjectDetailScreenProps {
  client: HttpCloudClient;
  token: string;
  projectId: string;
  onBack: () => void;
  onOpenSvg: (artifactName: string) => void;
  /** Hide back when shown in tablet split pane. */
  showBack?: boolean;
}

export function ProjectDetailScreen({
  client,
  token,
  projectId,
  onBack,
  onOpenSvg,
  showBack = true,
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
      setError(
        err instanceof CompanionError
          ? err.message
          : err instanceof Error
            ? err.message
            : "Failed to load project",
      );
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
      setError(
        err instanceof CompanionError
          ? err.message
          : err instanceof Error
            ? err.message
            : "Decision failed",
      );
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
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      {showBack ? (
        <Pressable onPress={onBack} style={styles.back}>
          <ArrowLeft size={18} color={tokens.accent} weight="regular" />
          <Text style={styles.link}>Projects</Text>
        </Pressable>
      ) : null}
      <Text style={styles.title}>{detail?.name ?? "Project"}</Text>
      {detail !== null ? (
        <Text style={styles.meta}>
          {detail.slug} · {detail.updated_at}
        </Text>
      ) : null}
      {loading ? <ActivityIndicator color={tokens.accent} /> : null}
      {error !== null ? <ErrorText>{error}</ErrorText> : null}

      <View style={styles.sectionHead}>
        <ListBullets size={16} color={tokens.text} weight="regular" />
        <SectionLabel>BOM</SectionLabel>
      </View>
      {bomLines.length === 0 && !loading ? (
        <Muted>No BOM lines in snapshot.</Muted>
      ) : null}
      {bomLines.map((item, i) => (
        <View key={`${item.ref}-${i}`} style={styles.row}>
          <Text style={styles.rowRef}>{item.ref}</Text>
          <Text style={styles.rowVal}>
            {item.value} ×{item.qty}
          </Text>
        </View>
      ))}

      <View style={styles.sectionHead}>
        <Circuitry size={16} color={tokens.text} weight="regular" />
        <SectionLabel>Schematics</SectionLabel>
      </View>
      {svgArtifacts.map((a) => (
        <Card key={a.name} onPress={() => onOpenSvg(a.name)} style={styles.cardGap}>
          <Text style={styles.cardTitle}>{a.name}</Text>
          <Muted>{`${a.bytes} bytes · SVG`}</Muted>
        </Card>
      ))}
      {svgArtifacts.length === 0 && !loading ? <Muted>No SVG artifacts yet.</Muted> : null}

      <View style={styles.sectionHead}>
        <Sparkle size={16} color={tokens.text} weight="regular" />
        <SectionLabel>Pending proposals</SectionLabel>
      </View>
      {proposals.length === 0 && !loading ? <Muted>Nothing pending.</Muted> : null}
      {proposals.map((p) => (
        <Card key={p.id} style={styles.cardGap}>
          <Text style={styles.cardTitle}>{p.title}</Text>
          <Muted>{p.summary}</Muted>
          <View style={styles.actions}>
            <PrimaryButton
              label="Approve"
              busy={busyId === p.id}
              disabled={busyId === p.id}
              onPress={() => void decide(p.id, "approve")}
              style={styles.actionBtn}
            />
            <PrimaryButton
              label="Reject"
              danger
              busy={busyId === p.id}
              disabled={busyId === p.id}
              onPress={() => void decide(p.id, "reject")}
              style={styles.actionBtn}
            />
          </View>
          <View style={styles.actionIcons}>
            <Check size={14} color={tokens.success} weight="bold" />
            <X size={14} color={tokens.danger} weight="bold" />
          </View>
        </Card>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  content: {
    paddingHorizontal: tokens.space5,
    paddingTop: 56,
    paddingBottom: 48,
    gap: tokens.space2,
  },
  back: {
    flexDirection: "row",
    alignItems: "center",
    gap: tokens.space2,
    marginBottom: tokens.space2,
  },
  link: { color: tokens.accent, fontWeight: "500" },
  title: {
    color: tokens.text,
    fontSize: 26,
    fontWeight: "700",
    letterSpacing: -0.4,
  },
  meta: { color: tokens.text2, fontSize: 13, marginBottom: tokens.space2 },
  sectionHead: {
    flexDirection: "row",
    alignItems: "center",
    gap: tokens.space2,
    marginTop: tokens.space4,
  },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: tokens.border,
  },
  rowRef: { color: tokens.text2, width: "40%", fontSize: 13 },
  rowVal: { color: tokens.text, flex: 1, textAlign: "right", fontSize: 13 },
  cardGap: { marginTop: tokens.space2 },
  cardTitle: { color: tokens.text, fontWeight: "600", marginBottom: 4 },
  actions: { flexDirection: "row", gap: tokens.space2, marginTop: tokens.space3 },
  actionBtn: { flex: 1 },
  actionIcons: { display: "none" },
});
