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
import {
  ArrowClockwise,
  FolderSimple,
  SignOut,
} from "phosphor-react-native";
import type { HttpCloudClient, ProjectListItem } from "../api/cloudHttp.js";
import { CompanionError } from "../native/types.js";
import { tokens } from "../theme.js";
import { BrandTile, Card, ErrorText, Muted } from "../ui/primitives.js";

export interface ProjectListScreenProps {
  client: HttpCloudClient;
  token: string;
  accountEmail: string;
  accountPlan: string;
  selectedId?: string | null;
  onOpenProject: (id: string) => void;
  onSignOut: () => void;
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
  onSignOut,
  embedded = false,
}: ProjectListScreenProps): JSX.Element {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [projects, setProjects] = useState<ProjectListItem[]>([]);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await client.listProjects(token);
      setProjects(list);
    } catch (err) {
      setError(
        err instanceof CompanionError
          ? err.message
          : err instanceof Error
            ? err.message
            : "Failed to load projects",
      );
    } finally {
      setLoading(false);
    }
  }, [client, token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return (
    <View style={[styles.root, embedded && styles.rootEmbedded]}>
      <View style={styles.header}>
        <View style={styles.brandRow}>
          <BrandTile size={36} />
          <View style={styles.flex}>
            <Text style={styles.brand}>Tokito</Text>
            <Text style={styles.sub} numberOfLines={1}>
              {accountEmail} · {accountPlan}
            </Text>
          </View>
        </View>
        <View style={styles.headerActions}>
          <Pressable
            accessibilityLabel="Refresh projects"
            onPress={() => void reload()}
            style={styles.iconBtn}
          >
            <ArrowClockwise size={20} color={tokens.accent} weight="regular" />
          </Pressable>
          <Pressable accessibilityLabel="Sign out" onPress={onSignOut} style={styles.iconBtn}>
            <SignOut size={20} color={tokens.text2} weight="regular" />
          </Pressable>
        </View>
      </View>

      <View style={styles.row}>
        <FolderSimple size={18} color={tokens.text} weight="regular" />
        <Text style={styles.h2}>Projects</Text>
      </View>

      {loading ? (
        <ActivityIndicator color={tokens.accent} style={{ marginTop: tokens.space5 }} />
      ) : null}
      {error !== null ? <ErrorText>{error}</ErrorText> : null}

      {!loading && projects.length === 0 && error === null ? (
        <Card style={{ marginTop: tokens.space3 }}>
          <Muted>
            No synced projects yet. On desktop: project kebab → Share to Companion.
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
              <Text style={styles.cardMeta}>
                {item.slug} · {item.updated_at}
              </Text>
            </Card>
          );
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: tokens.bg,
    paddingHorizontal: tokens.space5,
    paddingTop: 56,
  },
  rootEmbedded: {
    backgroundColor: tokens.bgChrome,
    borderRightWidth: 1,
    borderRightColor: tokens.border,
    paddingTop: 40,
  },
  header: {
    flexDirection: "row",
    alignItems: "flex-start",
    marginBottom: tokens.space5,
    gap: tokens.space3,
  },
  brandRow: { flex: 1, flexDirection: "row", alignItems: "center", gap: tokens.space3 },
  flex: { flex: 1 },
  brand: { color: tokens.text, fontSize: 22, fontWeight: "700", letterSpacing: -0.3 },
  sub: { color: tokens.text2, marginTop: 2, fontSize: 12 },
  headerActions: { flexDirection: "row", gap: tokens.space1 },
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
  cardSelected: {
    borderColor: tokens.accent,
    backgroundColor: tokens.accentSoft,
  },
  cardTitle: { color: tokens.text, fontSize: 16, fontWeight: "600" },
  cardMeta: { color: tokens.text2, marginTop: 6, fontSize: 12 },
});
