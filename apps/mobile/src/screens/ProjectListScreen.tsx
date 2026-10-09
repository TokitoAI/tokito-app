import type { JSX } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import {
  ArrowClockwise,
  Camera,
  FolderSimple,
  SignOut,
  GearSix,
} from "phosphor-react-native";
import type { CloudClient } from "../native/bridge";
import type { ProjectListItem } from "../api/cloudHttp";
import { CompanionError } from "../native/types";
import { tokens } from "../theme";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";
import { mergeProjectList } from "../sync/projectList";
import { BrandTile, Card, ErrorText, Muted, PrimaryButton } from "../ui/primitives";

export interface ProjectListScreenProps {
  client: CloudClient;
  token: string;
  accountEmail: string;
  accountPlan: string;
  selectedId?: string | null;
  onOpenProject: (id: string) => void;
  onStartBench: () => void;
  onSignOut: () => void;
  onManageModels: () => void;
  /** When embedded as tablet master pane, tighten chrome. */
  embedded?: boolean;
}

export function ProjectListScreen({
  client,
  token,
  accountEmail,
  accountPlan,
  selectedId = null,
  onOpenProject,
  onStartBench,
  onSignOut,
  onManageModels,
  embedded = false,
}: ProjectListScreenProps): JSX.Element {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [publishedProjects, setProjects] = useState<ProjectListItem[]>([]);
  const request = useRef(0);
  const realtime = useRealtimeWorkspace();
  const projects = useMemo(() => mergeProjectList(publishedProjects, realtime.resources), [publishedProjects, realtime.resources]);

  const reload = useCallback(async () => {
    const generation = ++request.current;
    setLoading(true);
    setError(null);
    try {
      const list = await client.listProjects(token);
      if (request.current !== generation) return;
      setProjects(list);
    } catch (err) {
      if (request.current !== generation) return;
      setError(
        err instanceof CompanionError
          ? err.message
          : err instanceof Error
            ? err.message
            : "Failed to load projects",
      );
    } finally {
      if (request.current === generation) setLoading(false);
    }
  }, [client, token]);

  useEffect(() => {
    setProjects([]);
    void reload();
    return () => { request.current++; };
  }, [reload]);

  return (
    <View style={[styles.root, embedded && styles.rootEmbedded]}>
      <View style={styles.header}>
        <View style={styles.brandRow}>
          <BrandTile size={36} />
          <View style={styles.flex}>
            <Text style={styles.brand} numberOfLines={1}>Tokito</Text>
            <Text style={styles.sub} numberOfLines={1}>
              {accountEmail}
            </Text>
            <View style={styles.accountMeta}>
              <View style={styles.planPill}><Text style={styles.planText}>{accountPlan}</Text></View>
              <View style={[styles.syncDot, realtime.status === "live" && styles.syncDotLive]} />
              <Text style={[styles.sync, realtime.status === "live" && styles.syncLive]}>{realtime.status === "live" ? "Project updates connected" : "Project updates offline"}</Text>
            </View>
          </View>
        </View>
        <View style={styles.headerActions}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Manage local models"
            onPress={onManageModels}
            style={styles.iconBtn}
          >
            <GearSix size={20} color={tokens.text2} weight="regular" />
          </Pressable>
          <Pressable
            accessibilityLabel="Refresh projects"
            accessibilityRole="button"
            onPress={() => void reload()}
            style={styles.iconBtn}
          >
            <ArrowClockwise size={20} color={tokens.accent} weight="regular" />
          </Pressable>
          <Pressable accessibilityRole="button" accessibilityLabel="Sign out" onPress={onSignOut} style={styles.iconBtn}>
            <SignOut size={20} color={tokens.text2} weight="regular" />
          </Pressable>
        </View>
      </View>

      <View style={styles.row}>
        <FolderSimple size={18} color={tokens.text} weight="regular" />
        <Text style={styles.h2}>Projects</Text>
        <Text style={styles.count}>{projects.length}</Text>
      </View>

      <PrimaryButton
        label="Start bench session"
        icon={<Camera size={18} color={tokens.accentInk} weight="regular" />}
        onPress={onStartBench}
        style={styles.benchButton}
      />

      {loading ? (
        <ActivityIndicator color={tokens.accent} style={{ marginTop: tokens.space5 }} />
      ) : null}
      {error !== null ? <ErrorText>{error}</ErrorText> : null}

      {!loading && projects.length === 0 && error === null ? (
        <Card style={{ marginTop: tokens.space3 }}>
          <Muted>
            No Cloud projects are available for this account yet.
          </Muted>
        </Card>
      ) : null}

      <FlatList
        data={projects}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ gap: tokens.space2, paddingBottom: 40 }}
        renderItem={({ item }) => {
          const selected = selectedId === item.id;
          return (
            <Card
              onPress={() => onOpenProject(item.id)}
              style={selected ? styles.cardSelected : undefined}
            >
              <Text style={styles.cardTitle}>{item.name}</Text>
              <Text style={styles.cardSlug} numberOfLines={1}>{item.slug}</Text>
              <Text style={styles.cardMeta}>{formatUpdatedAt(item.updated_at)}</Text>
            </Card>
          );
        }}
      />
    </View>
  );
}

function formatUpdatedAt(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return "Update time unavailable";
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 60) return "Updated just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `Updated ${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `Updated ${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `Updated ${days}d ago`;
  return `Updated ${new Date(timestamp).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })}`;
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: tokens.bg,
    paddingHorizontal: tokens.space5,
    paddingTop: 16,
  },
  rootEmbedded: {
    backgroundColor: tokens.bgChrome,
    borderRightWidth: 1,
    borderRightColor: tokens.border,
    paddingTop: 12,
    paddingHorizontal: tokens.space4,
  },
  header: {
    flexDirection: "column",
    alignItems: "stretch",
    marginBottom: tokens.space4,
    gap: tokens.space3,
  },
  brandRow: { width: "100%", flexShrink: 0, flexDirection: "row", alignItems: "center", gap: tokens.space3 },
  flex: { flex: 1 },
  brand: { color: tokens.text, fontSize: 22, fontWeight: "700", letterSpacing: -0.3 },
  sub: { color: tokens.text2, marginTop: 2, fontSize: 12 },
  accountMeta: { flexDirection: "row", alignItems: "center", gap: 6, marginTop: 6 },
  planPill: { paddingHorizontal: 7, paddingVertical: 2, borderRadius: 8, backgroundColor: tokens.accent2Soft },
  planText: { color: tokens.text2, fontSize: 9, fontWeight: "600", textTransform: "uppercase" },
  syncDot: { width: 6, height: 6, borderRadius: 3, backgroundColor: tokens.warning },
  syncDotLive: { backgroundColor: tokens.success },
  sync: { color: tokens.text2, fontSize: 10, textTransform: "capitalize" },
  syncLive: { color: tokens.accent },
  headerActions: { flexDirection: "row", justifyContent: "flex-end", gap: tokens.space2 },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: tokens.radiusSm,
    backgroundColor: tokens.card,
    borderWidth: 1,
    borderColor: tokens.border,
    alignItems: "center",
    justifyContent: "center",
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: tokens.space2,
    marginBottom: tokens.space3,
  },
  h2: { color: tokens.text, fontSize: 16, fontWeight: "600" },
  count: { color: tokens.text3, fontSize: 11, marginLeft: "auto", paddingHorizontal: 8, paddingVertical: 3, borderRadius: 10, backgroundColor: tokens.card },
  benchButton: { marginBottom: tokens.space4 },
  cardSelected: {
    borderColor: tokens.accent,
    backgroundColor: tokens.accentSoft,
  },
  cardTitle: { color: tokens.text, fontSize: 16, fontWeight: "600" },
  cardSlug: { color: tokens.text2, marginTop: 5, fontSize: 12 },
  cardMeta: { color: tokens.text3, marginTop: 4, fontSize: 10 },
});
