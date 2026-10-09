import type { JSX } from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from "react-native";
import { ArrowLeft, Camera, ChatCircleDots, Circuitry, ClipboardText, Sparkle } from "phosphor-react-native";
import type { ProjectDetail } from "../api/cloudHttp";
import type { CloudClient } from "../native/bridge";
import { projectFailure } from "../sync/projectFailure";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";
import { TABLET_MIN_WIDTH, tokens } from "../theme";
import { Card, ErrorText, Muted, PrimaryButton, SectionLabel } from "../ui/primitives";
import { ProjectChat } from "../chat/ProjectChat";
import { schematicSnapshots } from "../viewer/snapshotRuntime";
import { ProjectReview } from "../review/ProjectReview";
import { chatSheets } from "../chat/sheetContext";
import { canonicalBom } from "../sync/canonicalBom";

type Section = "design" | "chat" | "review";

export interface ProjectDetailScreenProps {
  client: CloudClient;
  token: string;
  projectId: string;
  onBack: () => void;
  onOpenSvg: (artifactName: string) => void;
  onOpenLiveSchematic: (designId: string) => void;
  onStartBench: (designId: string | null) => void;
  /** Back is hidden only in the optional tablet master/detail enhancement. */
  showBack?: boolean;
}

export function ProjectDetailScreen({ client, token, projectId, onBack, onOpenSvg, onOpenLiveSchematic, onStartBench, showBack = true }: ProjectDetailScreenProps): JSX.Element {
  const { width, height } = useWindowDimensions();
  const tablet = width >= TABLET_MIN_WIDTH && height >= 600;
  const realtime = useRealtimeWorkspace();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [selectedDesignId, setSelectedDesignId] = useState<string | null>(null);
  const [section, setSection] = useState<Section>("design");
  const request = useRef(0);

  const liveProject = realtime.getResource(projectId, null, "project")?.payload as Record<string, unknown> | undefined;
  const designResources = realtime.resources.filter((item) => item.project_id === projectId && item.resource === "design");
  const liveDesigns = designResources
    .map((item) => {
      const payload = item.payload as Record<string, unknown> & { deleted?: boolean };
      return { id: item.design_id ?? "", name: typeof payload.name === "string" ? payload.name : item.design_id ?? "Design", deleted: payload.deleted === true };
    })
    .filter((item) => item.id.length > 0 && !item.deleted);
  const snapshotDesigns = detail?.designs.map((item) => ({ id: item.id, name: item.name })) ?? [];
  const designs = designResources.length > 0 ? liveDesigns : snapshotDesigns;
  const activeDesignId = designs.some(design => design.id === selectedDesignId) ? selectedDesignId : designs[0]?.id ?? null;
  const activeDesignName = designs.find((item) => item.id === activeDesignId)?.name ?? "Design";
  const bomResource = activeDesignId === null ? undefined : realtime.getResource(projectId, activeDesignId, "bom");
  const schematicResource = activeDesignId === null ? undefined : realtime.getResource(projectId, activeDesignId, "schematic");
  const projectedBom = bomResource === undefined ? null : canonicalBom(bomResource.payload, schematicResource?.payload);
  const displayedBom = projectedBom?.lines ?? detail?.designs.find((item) => item.id === activeDesignId)?.bom_lines ?? [];
  const hasSnapshot = detail !== null && activeDesignId !== null && schematicSnapshots(detail, activeDesignId).length > 0;
  const svgArtifacts = detail?.artifact_manifest.filter((item) => item.name.endsWith(".svg")) ?? [];

  const reload = useCallback(async () => {
    const generation = ++request.current;
    setLoading(true);
    setError(null);
    const [project] = await Promise.allSettled([client.getProject(token, projectId)]);
    if (generation !== request.current) return;
    if (project.status === "fulfilled") setDetail(project.value);
    else setError(projectFailure(project.reason));
    setLoading(false);
  }, [client, token, projectId]);

  useEffect(() => {
    setDetail(null); setSelectedDesignId(null);
    void reload();
    return () => { request.current++; };
  }, [reload]);

  const projectName = typeof liveProject?.name === "string" ? liveProject.name : detail?.name ?? "Project";
  return (
    <View style={styles.root}>
      <View style={styles.header}>
        {showBack ? <Pressable accessibilityRole="button" accessibilityLabel="Back to projects" onPress={onBack} style={styles.back}><ArrowLeft size={21} color={tokens.text} /></Pressable> : null}
        <View style={styles.identity}><Text style={styles.eyebrow}>PROJECT</Text><Text style={styles.title} numberOfLines={1}>{projectName}</Text><Text style={styles.subtitle} numberOfLines={1}>{activeDesignName}</Text></View>
        <View accessibilityLabel={`Project updates ${realtime.status}`} style={styles.liveBadge}><View style={[styles.liveDot, realtime.status !== "live" && styles.liveDotWaiting]} /><Text style={styles.liveText}>{realtime.status === "live" ? "Updates connected" : "Updates offline"}</Text></View>
      </View>
      {designs.length > 1 ? <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.designRail} contentContainerStyle={styles.designTabs}>{designs.map((design) => <Pressable key={design.id} accessibilityRole="tab" accessibilityState={{ selected: activeDesignId === design.id }} onPress={() => setSelectedDesignId(design.id)} style={[styles.designTab, activeDesignId === design.id && styles.designTabActive]}><Text style={[styles.designTabText, activeDesignId === design.id && styles.designTabTextActive]}>{design.name}</Text></Pressable>)}</ScrollView> : null}
      <View accessibilityRole="tablist" style={[styles.nav, tablet && styles.navTablet]}>
        <Tab label="Design" icon={<Circuitry size={20} color={section === "design" ? tokens.text : tokens.text2} />} selected={section === "design"} onPress={() => setSection("design")} />
        <Tab label="Chat" icon={<ChatCircleDots size={20} color={section === "chat" ? tokens.text : tokens.text2} />} selected={section === "chat"} onPress={() => setSection("chat")} />
        <Tab label="Review" icon={<Sparkle size={20} color={section === "review" ? tokens.text : tokens.text2} />} selected={section === "review"} onPress={() => setSection("review")} />
      </View>
      {section === "chat" && activeDesignId !== null ? <ProjectChat key={`${projectId}:${activeDesignId}`} baseUrl={client.baseUrl()} token={token} project={projectId} design={activeDesignId} version={realtime.getResource(projectId, activeDesignId, "schematic")?.version ?? 0} sheets={chatSheets(realtime.getResource(projectId, activeDesignId, "schematic")?.payload)} /> : <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={[styles.content, tablet && styles.contentTablet]}>
        {loading ? <ActivityIndicator color={tokens.accent} /> : null}
        {error !== null ? <View style={{ gap: 8 }}><ErrorText>{error}</ErrorText><PrimaryButton label="Retry previews" onPress={() => { void reload(); }} disabled={loading} style={{ alignSelf: "flex-start" }} /></View> : null}
        {realtime.error !== null ? <ErrorText>{realtime.error}</ErrorText> : null}
        {section === "design" ? <View style={[styles.panel, tablet && styles.panelTablet]}>
          <View style={[styles.designColumns, tablet && styles.designColumnsTablet]}>
            <View style={styles.designPrimary}>
              <Card style={styles.benchCard}>
                <View style={styles.schematicIcon}><Camera size={28} color={tokens.accent} /></View>
                <View style={styles.flex}><Text style={styles.cardTitle}>Continue at the bench</Text><Muted>Open a private, on-device session with this project and design attached.</Muted></View>
                <PrimaryButton label="Start" onPress={() => onStartBench(activeDesignId)} style={styles.benchAction} />
              </Card>
              <Card onPress={hasSnapshot && activeDesignId !== null ? () => onOpenLiveSchematic(activeDesignId) : undefined} style={[styles.schematicCard, tablet && styles.schematicCardTablet, !hasSnapshot && styles.cardDisabled]}>
                <View style={styles.schematicIcon}><Circuitry size={30} color={tokens.accent} /></View>
                <View style={styles.flex}><Text style={styles.cardTitle}>{hasSnapshot ? "View schematic snapshot" : "No published snapshot"}</Text><Muted>{hasSnapshot ? "Inspect the published drawing. Newer edits may not be included." : "Publish a drawing from Tokito desktop to preview this design."}</Muted></View>
                {hasSnapshot ? <Text style={styles.open}>View</Text> : null}
              </Card>
              {svgArtifacts.length > 0 ? <View style={styles.sectionHead}><SectionLabel>Published previews</SectionLabel></View> : null}
              {svgArtifacts.map((artifact) => <Card key={artifact.name} onPress={() => onOpenSvg(artifact.name)} style={styles.previewCard}><Text style={styles.previewName} numberOfLines={1}>{artifact.name}</Text><Text style={styles.open}>View</Text></Card>)}
            </View>
            <View style={styles.bomPane}>
              <View style={styles.sectionHead}><ClipboardText size={18} color={tokens.text} /><SectionLabel>Bill of materials</SectionLabel><Text style={styles.count}>{displayedBom.length}</Text></View>
              <Text style={styles.help}>This Cloud bill of materials is read-only here.</Text>
              {projectedBom?.error ? <ErrorText>{projectedBom.error}</ErrorText> : displayedBom.length === 0 ? <Empty title="No BOM entries" body="This Cloud workspace does not currently contain BOM entries." /> : null}
              {displayedBom.map((line, index) => <View key={`${line.ref}-${index}`} style={styles.bomRow}><View style={styles.refBadge}><Text style={styles.refText}>{line.ref}</Text></View><View style={styles.flex}><Text style={styles.bomValue}>{line.value || "Unspecified"}</Text><Text style={styles.bomFootprint}>{line.footprint || "No footprint"}</Text></View><Text style={styles.qty}>×{line.qty}</Text></View>)}
            </View>
          </View>
        </View> : null}
        {section === "chat" ? <Empty title="Select a design first" body="Open a design in this project to start its conversation." /> : null}
        {section === "review" ? activeDesignId === null ? <Empty title="Select a design first" body="Open a design to review its proposed changes." /> : <ProjectReview key={`${projectId}:${activeDesignId}`} base={client.baseUrl()} token={token} project={projectId} design={activeDesignId} revision={realtime.getResource(projectId, activeDesignId, "schematic")?.version ?? 0} bomRevision={realtime.getResource(projectId, activeDesignId, "bom")?.version ?? 0} summaryVersion={realtime.getResource(projectId, activeDesignId, "conversations")?.version ?? 0} /> : null}
      </ScrollView>}
    </View>
  );
}

function Tab({ label, icon, selected, onPress }: { label: string; icon: JSX.Element; selected: boolean; onPress: () => void }): JSX.Element {
  return <Pressable accessibilityRole="tab" accessibilityState={{ selected }} onPress={onPress} style={[styles.navItem, selected && styles.navItemActive]}>{icon}<Text style={[styles.navText, selected && styles.navTextActive]}>{label}</Text></Pressable>;
}
function Empty({ title, body }: { title: string; body: string }): JSX.Element { return <View style={styles.empty}><Text style={styles.emptyTitle}>{title}</Text><Muted>{body}</Muted></View>; }

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  header: { minHeight: 94, paddingTop: 36, paddingHorizontal: 16, paddingBottom: 10, flexDirection: "row", alignItems: "center", gap: 11, borderBottomWidth: 1, borderBottomColor: tokens.border, backgroundColor: tokens.bgChrome },
  back: { width: 44, height: 44, borderRadius: 22, alignItems: "center", justifyContent: "center", backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.border },
  identity: { flex: 1, minWidth: 0 }, eyebrow: { color: tokens.text3, fontSize: 9, fontWeight: "700", letterSpacing: 1.2 }, title: { color: tokens.text, fontSize: 20, fontWeight: "700" }, subtitle: { color: tokens.text2, fontSize: 12 },
  liveBadge: { height: 32, paddingHorizontal: 10, borderRadius: 16, flexDirection: "row", alignItems: "center", gap: 6, backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.border }, liveDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: tokens.success }, liveDotWaiting: { backgroundColor: tokens.warning }, liveText: { color: tokens.text2, fontSize: 11, fontWeight: "600" },
  designRail: { flexGrow: 0, maxHeight: 52, borderBottomWidth: 1, borderBottomColor: tokens.borderSoft }, designTabs: { paddingHorizontal: 16, paddingVertical: 8, gap: 7 }, designTab: { minHeight: 36, justifyContent: "center", paddingHorizontal: 14, borderRadius: 18, borderWidth: 1, borderColor: tokens.border, backgroundColor: tokens.card }, designTabActive: { borderColor: tokens.accent, backgroundColor: tokens.accentSoft }, designTabText: { color: tokens.text2, fontSize: 13 }, designTabTextActive: { color: tokens.accent, fontWeight: "600" },
  nav: { minHeight: 58, flexDirection: "row", padding: 6, gap: 4, borderBottomWidth: 1, borderBottomColor: tokens.border, backgroundColor: tokens.bgChrome }, navTablet: { paddingHorizontal: 24 }, navItem: { flex: 1, minHeight: 46, borderRadius: tokens.radiusSm, flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 6 }, navItemActive: { backgroundColor: tokens.cardHover }, navText: { color: tokens.text2, fontSize: 12, fontWeight: "500" }, navTextActive: { color: tokens.text, fontWeight: "700" },
  content: { padding: 16, paddingBottom: 48, gap: 12 }, contentTablet: { padding: 24 }, panel: { width: "100%", maxWidth: 760, alignSelf: "center", gap: 12 }, panelTablet: { maxWidth: 1400 }, chatPanelTablet: { maxWidth: 960 }, designColumns: { gap: 16 }, designColumnsTablet: { flexDirection: "row", alignItems: "flex-start", gap: 20 }, designPrimary: { flex: 1, gap: 12 }, bomPane: { flex: 1.12, gap: 12, minWidth: 0 }, sectionHead: { minHeight: 30, flexDirection: "row", alignItems: "center", gap: 7 }, help: { color: tokens.text2, fontSize: 13, lineHeight: 19, marginTop: -8 }, count: { marginLeft: "auto", color: tokens.text2, fontSize: 12, paddingHorizontal: 9, paddingVertical: 3, borderRadius: 12, backgroundColor: tokens.card },
  schematicCard: { minHeight: 104, flexDirection: "row", alignItems: "center", gap: 12, borderColor: "rgba(8,201,170,0.35)", backgroundColor: tokens.accentSoft }, schematicCardTablet: { minHeight: 150 }, cardDisabled: { opacity: 0.7, borderColor: tokens.border, backgroundColor: tokens.card }, schematicIcon: { width: 54, height: 54, borderRadius: 27, alignItems: "center", justifyContent: "center", backgroundColor: tokens.brandTile }, cardTitle: { color: tokens.text, fontSize: 15, fontWeight: "700", marginBottom: 4 }, open: { color: tokens.accent, fontSize: 12, fontWeight: "700" }, flex: { flex: 1, minWidth: 0 },
  benchCard: { minHeight: 104, gap: 12, borderColor: "rgba(8,201,170,0.35)", backgroundColor: tokens.accentSoft },
  benchAction: { alignSelf: "flex-start", minWidth: 112 },
  bomRow: { minHeight: 62, flexDirection: "row", alignItems: "center", gap: 11, paddingVertical: 9, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: tokens.border }, refBadge: { minWidth: 52, minHeight: 34, alignItems: "center", justifyContent: "center", paddingHorizontal: 8, borderRadius: tokens.radiusSm, backgroundColor: tokens.card }, refText: { color: tokens.accent, fontSize: 12, fontWeight: "700" }, bomValue: { color: tokens.text, fontSize: 14, fontWeight: "600" }, bomFootprint: { color: tokens.text2, fontSize: 11, marginTop: 2 }, qty: { color: tokens.text2, fontSize: 13, fontWeight: "600" },
  previewCard: { minHeight: 54, flexDirection: "row", alignItems: "center", gap: 12 }, previewName: { flex: 1, color: tokens.text, fontSize: 13, fontWeight: "600" }, empty: { minHeight: 132, alignItems: "center", justifyContent: "center", gap: 7, padding: 16, borderWidth: 1, borderStyle: "dashed", borderColor: tokens.borderStrong, borderRadius: tokens.radiusMd, backgroundColor: tokens.bgChrome }, emptyTitle: { color: tokens.text, fontSize: 16, fontWeight: "700" },
  chatHistory: { gap: 8 }, chatBubble: { alignSelf: "flex-start", maxWidth: "88%", padding: 12, borderRadius: 14, borderBottomLeftRadius: 4, backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.border }, chatBubbleUser: { alignSelf: "flex-end", borderBottomLeftRadius: 14, borderBottomRightRadius: 4, borderColor: "rgba(8,201,170,0.4)", backgroundColor: tokens.accentSoft }, chatRole: { color: tokens.text2, fontSize: 10, fontWeight: "700", textTransform: "uppercase", marginBottom: 4 }, chatText: { color: tokens.text, fontSize: 14, lineHeight: 20 }, composer: { flexDirection: "row", alignItems: "flex-end", gap: 8, marginTop: 5 }, chatInput: { flex: 1, minHeight: 52, maxHeight: 130, paddingHorizontal: 13, paddingVertical: 11, color: tokens.text, fontSize: 15, textAlignVertical: "top", borderRadius: 15, borderWidth: 1, borderColor: tokens.borderStrong, backgroundColor: tokens.card }, send: { minWidth: 72, minHeight: 52, borderRadius: 15 }, proposalGrid: { gap: 12 }, proposalGridTablet: { flexDirection: "row", flexWrap: "wrap" }, proposalCard: { gap: 4 }, proposalCardTablet: { width: "49%", minHeight: 150 }, actions: { flexDirection: "row", gap: 8, marginTop: 10 }, action: { flex: 1, minHeight: 48 },
});
