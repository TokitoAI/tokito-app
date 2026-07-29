import type { JSX } from "react";
import { useCallback, useEffect, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  ArrowLeft,
  Circuitry,
  ListBullets,
  Sparkle,
} from "phosphor-react-native";
import type {
  BomLine,
  ProjectDetail,
  Proposal,
} from "../api/cloudHttp";
import type { CloudClient } from "../native/bridge";
import { CompanionError } from "../native/types";
import { tokens } from "../theme";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";
import { newStableId } from "../sync/ids";
import {
  Card,
  ErrorText,
  Muted,
  PrimaryButton,
  SectionLabel,
} from "../ui/primitives";

export interface ProjectDetailScreenProps {
  client: CloudClient;
  token: string;
  projectId: string;
  onBack: () => void;
  onOpenSvg: (artifactName: string) => void;
  onOpenLiveSchematic: (designId: string) => void;
  /** Hide back when shown in tablet split pane. */
  showBack?: boolean;
}

export function ProjectDetailScreen({
  client,
  token,
  projectId,
  onBack,
  onOpenSvg,
  onOpenLiveSchematic,
  showBack = true,
}: ProjectDetailScreenProps): JSX.Element {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [proposals, setProposals] = useState<Proposal[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [selectedDesignId, setSelectedDesignId] = useState<string | null>(null);
  const [chatDraft, setChatDraft] = useState("");
  const [projectNameDraft, setProjectNameDraft] = useState("");
  const [designNameDraft, setDesignNameDraft] = useState("");
  const [newDesignName, setNewDesignName] = useState("");
  const realtime = useRealtimeWorkspace();

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
      if (suggestionPayload?.items && activeDesignId !== null) {
        await realtime.update(projectId, activeDesignId, "suggestions", {
          items: suggestionPayload.items.map((item) =>
            item.id === id
              ? { ...item, status: decision === "approve" ? "approved" : "rejected", decision }
              : item),
        });
      } else {
        await client.decideProposal(token, projectId, id, decision);
        await reload();
      }
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

  const liveDesigns = realtime.resources
    .filter((item) => item.project_id === projectId && item.resource === "design")
    .map((item) => {
      const payload = item.payload as Record<string, unknown> & { deleted?: boolean };
      return {
        id: item.design_id ?? "",
        name: typeof payload.name === "string" ? payload.name : item.design_id ?? "Design",
        deleted: payload.deleted === true,
      };
    })
    .filter((item) => item.id.length > 0 && !item.deleted);
  const activeDesignId = selectedDesignId ?? liveDesigns[0]?.id ?? detail?.designs[0]?.id ?? null;
  const liveProject = realtime.getResource(projectId, null, "project")?.payload as
    | Record<string, unknown>
    | undefined;
  useEffect(() => {
    if (liveProject !== undefined) {
      setError(null);
      setLoading(false);
    }
  }, [liveProject]);
  const bomPayload = activeDesignId === null
    ? undefined
    : realtime.getResource(projectId, activeDesignId, "bom")?.payload as
      | { lines?: BomLine[] }
      | undefined;
  const displayedBom = bomPayload?.lines ?? bomLines;
  const chatPayload = activeDesignId === null
    ? undefined
    : realtime.getResource(projectId, activeDesignId, "chat")?.payload as
      | { messages?: Array<{ id: string; role: string; content: string; created_at?: string }> }
      | undefined;
  const chatMessages = chatPayload?.messages ?? [];
  const suggestionPayload = activeDesignId === null
    ? undefined
    : realtime.getResource(projectId, activeDesignId, "suggestions")?.payload as
      | { items?: Proposal[] }
      | undefined;
  const displayedProposals = suggestionPayload?.items?.filter((item) => item.status === "pending") ?? proposals;
  const activeDesign = liveDesigns.find((item) => item.id === activeDesignId);

  useEffect(() => {
    setProjectNameDraft(
      typeof liveProject?.name === "string" ? liveProject.name : detail?.name ?? "",
    );
  }, [detail?.name, liveProject?.name]);
  useEffect(() => {
    setDesignNameDraft(activeDesign?.name ?? "");
  }, [activeDesign?.id, activeDesign?.name]);

  async function saveBom(lines: BomLine[]): Promise<void> {
    if (activeDesignId === null) return;
    await realtime.update(projectId, activeDesignId, "bom", { lines });
  }

  async function sendChat(): Promise<void> {
    const content = chatDraft.trim();
    if (content.length === 0 || activeDesignId === null) return;
    setChatDraft("");
    await realtime.update(projectId, activeDesignId, "chat", {
      ...(chatPayload ?? {}),
      messages: [
        ...chatMessages,
        {
          id: `companion-${Date.now()}-${Math.random().toString(16).slice(2)}`,
          role: "user",
          content,
          created_at: new Date().toISOString(),
        },
      ],
    });
  }

  async function renameProject(): Promise<void> {
    const name = projectNameDraft.trim();
    if (name.length === 0) return;
    await realtime.update(projectId, null, "project", {
      ...(liveProject ?? {}), id: projectId, name, updated_at: new Date().toISOString(),
    });
  }

  async function renameDesign(): Promise<void> {
    if (activeDesignId === null || designNameDraft.trim().length === 0) return;
    const current = realtime.getResource(projectId, activeDesignId, "design")?.payload as
      | Record<string, unknown>
      | undefined;
    await realtime.update(projectId, activeDesignId, "design", {
      ...(current ?? {}), id: activeDesignId, name: designNameDraft.trim(), updated_at: new Date().toISOString(),
    });
  }

  async function createDesign(): Promise<void> {
    const name = newDesignName.trim();
    if (name.length === 0) return;
    const id = newStableId();
    setNewDesignName("");
    await realtime.update(projectId, id, "design", {
      id, name, description: null, notes: null, sheet_count: 1,
      updated_at: new Date().toISOString(),
    });
    await realtime.update(projectId, id, "schematic", {
      schema_version: 2,
      sheets: [{
        id: "root", name: "Root", path: "/", grid: 40,
        page_size: { width: 9354.330708661417, height: 6614.173228346457 },
        title_block: {},
      }],
      symbols: [], wire_segments: [], junctions: [], net_labels: [],
      power_symbols: [], no_connects: [], text_items: [], buses: [],
      erc_markers: [],
    });
    await realtime.update(projectId, id, "bom", { lines: [] });
    await realtime.update(projectId, id, "chat", { messages: [] });
    await realtime.update(projectId, id, "suggestions", { items: [] });
    setSelectedDesignId(id);
  }

  function confirmDeleteDesign(): void {
    if (activeDesignId === null) return;
    const designId = activeDesignId;
    Alert.alert("Delete design?", "This deletes the design on every connected Tokito device.", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Delete", style: "destructive",
        onPress: () => void realtime.update(projectId, designId, "design", {
          id: designId, name: activeDesign?.name ?? "Deleted design", deleted: true,
          updated_at: new Date().toISOString(),
        }).then(() => setSelectedDesignId(null)),
      },
    ]);
  }

  function confirmDeleteProject(): void {
    Alert.alert("Delete project?", "This deletes the project and its designs on every connected device.", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Delete", style: "destructive",
        onPress: () => void realtime.update(projectId, null, "project", {
          ...(liveProject ?? {}), id: projectId, deleted: true,
          updated_at: new Date().toISOString(),
        }).then(onBack),
      },
    ]);
  }

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
      <Text style={styles.title}>
        {typeof liveProject?.name === "string" ? liveProject.name : detail?.name ?? "Project"}
      </Text>
      {detail !== null ? (
        <Text style={styles.meta}>
          {detail.slug} · {detail.updated_at}
        </Text>
      ) : null}
      {loading ? <ActivityIndicator color={tokens.accent} /> : null}
      {error !== null ? <ErrorText>{error}</ErrorText> : null}
      {realtime.error !== null ? <ErrorText>{realtime.error}</ErrorText> : null}
      {realtime.conflicts
        .filter((conflict) => conflict.command.project_id === projectId)
        .map((conflict) => (
          <Card key={conflict.command.command_id} style={styles.conflictCard}>
            <Text style={styles.cardTitle}>Changed on another device</Text>
            <Muted>
              {conflict.command.resource} version {conflict.currentVersion} conflicts with your queued edit.
            </Muted>
            <View style={styles.actions}>
              <PrimaryButton
                label="Use server"
                onPress={() => void realtime.resolveConflict(conflict.command.command_id, false)}
                style={styles.actionBtn}
              />
              <PrimaryButton
                label="Keep mine"
                danger
                onPress={() => void realtime.resolveConflict(conflict.command.command_id, true)}
                style={styles.actionBtn}
              />
            </View>
          </Card>
        ))}

      <View style={styles.sectionHead}>
        <SectionLabel>Workspace</SectionLabel>
      </View>
      <View style={styles.manageRow}>
        <TextInput
          accessibilityLabel="Project name"
          value={projectNameDraft}
          onChangeText={setProjectNameDraft}
          style={styles.manageInput}
        />
        <PrimaryButton label="Rename project" onPress={() => void renameProject()} />
      </View>
      <View style={styles.manageRow}>
        <TextInput
          accessibilityLabel="New design name"
          value={newDesignName}
          onChangeText={setNewDesignName}
          onSubmitEditing={() => void createDesign()}
          placeholder="New design"
          placeholderTextColor={tokens.text2}
          style={styles.manageInput}
        />
        <PrimaryButton
          label="Create design"
          disabled={newDesignName.trim().length === 0}
          onPress={() => void createDesign()}
        />
      </View>

      {liveDesigns.length > 0 ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.designTabs}>
          {liveDesigns.map((design) => (
            <Pressable
              key={design.id}
              onPress={() => setSelectedDesignId(design.id)}
              style={[styles.designTab, activeDesignId === design.id && styles.designTabActive]}
            >
              <Text style={activeDesignId === design.id ? styles.designTabTextActive : styles.designTabText}>
                {design.name}
              </Text>
            </Pressable>
          ))}
        </ScrollView>
      ) : null}
      {activeDesignId !== null ? (
        <View style={styles.manageRow}>
          <TextInput
            accessibilityLabel="Design name"
            value={designNameDraft}
            onChangeText={setDesignNameDraft}
            style={styles.manageInput}
          />
          <PrimaryButton label="Rename design" onPress={() => void renameDesign()} />
          <PrimaryButton label="Delete design" danger onPress={confirmDeleteDesign} />
        </View>
      ) : null}

      <View style={styles.sectionHead}>
        <ListBullets size={16} color={tokens.text} weight="regular" />
        <SectionLabel>BOM</SectionLabel>
      </View>
      {displayedBom.length === 0 && !loading ? (
        <Muted>No BOM lines in snapshot.</Muted>
      ) : null}
      {displayedBom.map((item, i) => (
        <EditableBomLine
          key={`${item.ref}-${i}`}
          line={item}
          editable={bomPayload !== undefined}
          onSave={(line) =>
            void saveBom(displayedBom.map((candidate, index) => index === i ? line : candidate))}
        />
      ))}

      <View style={styles.sectionHead}>
        <Sparkle size={16} color={tokens.text} weight="regular" />
        <SectionLabel>Design chat</SectionLabel>
      </View>
      {chatMessages.map((message) => (
        <View key={message.id} style={[styles.chatBubble, message.role === "user" && styles.chatBubbleUser]}>
          <Text style={styles.chatRole}>{message.role}</Text>
          <Text style={styles.chatText}>{message.content}</Text>
        </View>
      ))}
      {activeDesignId !== null ? (
        <View style={styles.chatComposer}>
          <TextInput
            value={chatDraft}
            onChangeText={setChatDraft}
            placeholder="Ask Tokito or continue the design chat"
            placeholderTextColor={tokens.text2}
            style={styles.chatInput}
            multiline
          />
          <PrimaryButton
            label="Send"
            disabled={chatDraft.trim().length === 0}
            onPress={() => void sendChat()}
          />
        </View>
      ) : null}

      <View style={styles.sectionHead}>
        <Circuitry size={16} color={tokens.text} weight="regular" />
        <SectionLabel>Schematics</SectionLabel>
      </View>
      {activeDesignId !== null && realtime.getResource(projectId, activeDesignId, "schematic") ? (
        <Card onPress={() => onOpenLiveSchematic(activeDesignId)} style={styles.cardGap}>
          <Text style={styles.cardTitle}>Open live schematic editor</Text>
          <Muted>Move, wire, label, annotate, rotate, mirror, and delete in real time.</Muted>
        </Card>
      ) : null}
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
      {displayedProposals.length === 0 && !loading ? <Muted>Nothing pending.</Muted> : null}
      {displayedProposals.map((p) => (
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
        </Card>
      ))}
      {projectId !== "00000000-0000-4000-8000-000000000001" ? (
        <PrimaryButton label="Delete project" danger onPress={confirmDeleteProject} />
      ) : null}
    </ScrollView>
  );
}

function EditableBomLine({
  line,
  editable,
  onSave,
}: {
  line: BomLine;
  editable: boolean;
  onSave: (line: BomLine) => void;
}): JSX.Element {
  const [draft, setDraft] = useState(line);
  useEffect(() => setDraft(line), [line]);
  if (!editable) {
    return (
      <View style={styles.row}>
        <Text style={styles.rowRef}>{line.ref}</Text>
        <Text style={styles.rowVal}>{line.value} ×{line.qty}</Text>
      </View>
    );
  }
  const dirty = JSON.stringify(draft) !== JSON.stringify(line);
  return (
    <View style={styles.bomEditorRow}>
      <Text style={styles.rowRef}>{line.ref}</Text>
      <TextInput
        value={draft.value}
        onChangeText={(value) => setDraft((current) => ({ ...current, value }))}
        style={[styles.bomInput, styles.bomValueInput]}
        placeholder="Value"
        placeholderTextColor={tokens.text2}
      />
      <TextInput
        value={String(draft.qty)}
        onChangeText={(value) => setDraft((current) => ({
          ...current,
          qty: Math.max(0, Number.parseInt(value || "0", 10) || 0),
        }))}
        style={[styles.bomInput, styles.bomQtyInput]}
        keyboardType="number-pad"
      />
      <Pressable
        accessibilityLabel={`Save ${line.ref}`}
        disabled={!dirty}
        onPress={() => onSave(draft)}
        style={[styles.bomSave, !dirty && styles.bomSaveDisabled]}
      >
        <Text style={styles.bomSaveText}>Save</Text>
      </Pressable>
    </View>
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
  designTabs: { gap: tokens.space2, paddingVertical: tokens.space2 },
  designTab: {
    borderWidth: 1,
    borderColor: tokens.border,
    borderRadius: tokens.radiusSm,
    paddingHorizontal: tokens.space3,
    paddingVertical: 8,
    backgroundColor: tokens.card,
  },
  manageRow: { flexDirection: "row", alignItems: "center", gap: tokens.space2 },
  manageInput: {
    flex: 1,
    color: tokens.text,
    backgroundColor: tokens.card,
    borderWidth: 1,
    borderColor: tokens.border,
    borderRadius: tokens.radiusSm,
    paddingHorizontal: tokens.space3,
    minHeight: 42,
  },
  designTabActive: { borderColor: tokens.accent, backgroundColor: tokens.accentSoft },
  designTabText: { color: tokens.text2, fontSize: 13 },
  designTabTextActive: { color: tokens.accent, fontSize: 13, fontWeight: "600" },
  bomEditorRow: { flexDirection: "row", alignItems: "center", gap: 6, paddingVertical: 5 },
  bomInput: {
    color: tokens.text,
    backgroundColor: tokens.card,
    borderWidth: 1,
    borderColor: tokens.border,
    borderRadius: tokens.radiusSm,
    paddingHorizontal: 8,
    paddingVertical: 7,
    fontSize: 13,
  },
  bomValueInput: { flex: 1 },
  bomQtyInput: { width: 48, textAlign: "center" },
  bomSave: { paddingHorizontal: 9, paddingVertical: 8, borderRadius: tokens.radiusSm, backgroundColor: tokens.accent },
  bomSaveDisabled: { opacity: 0.35 },
  bomSaveText: { color: tokens.bg, fontSize: 12, fontWeight: "600" },
  chatBubble: {
    alignSelf: "flex-start",
    maxWidth: "90%",
    backgroundColor: tokens.card,
    borderWidth: 1,
    borderColor: tokens.border,
    borderRadius: tokens.radiusSm,
    padding: tokens.space3,
  },
  chatBubbleUser: { alignSelf: "flex-end", borderColor: tokens.accent, backgroundColor: tokens.accentSoft },
  chatRole: { color: tokens.text2, textTransform: "uppercase", fontSize: 10, fontWeight: "600", marginBottom: 4 },
  chatText: { color: tokens.text, fontSize: 14, lineHeight: 20 },
  chatComposer: { gap: tokens.space2, marginTop: tokens.space2 },
  chatInput: {
    minHeight: 76,
    color: tokens.text,
    backgroundColor: tokens.card,
    borderWidth: 1,
    borderColor: tokens.border,
    borderRadius: tokens.radiusSm,
    padding: tokens.space3,
    textAlignVertical: "top",
  },
  cardGap: { marginTop: tokens.space2 },
  conflictCard: { marginTop: tokens.space2, borderColor: tokens.danger },
  cardTitle: { color: tokens.text, fontWeight: "600", marginBottom: 4 },
  actions: { flexDirection: "row", gap: tokens.space2, marginTop: tokens.space3 },
  actionBtn: { flex: 1 },
});
