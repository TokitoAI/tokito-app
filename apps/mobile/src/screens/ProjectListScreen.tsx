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
import type { HttpCloudClient, ProjectListItem } from "../api/cloudHttp.js";
import { colors } from "../theme.js";

export interface ProjectListScreenProps {
  client: HttpCloudClient;
  token: string;
  accountEmail: string;
  accountPlan: string;
  onOpenProject: (id: string) => void;
  onSignOut: () => void;
}

export function ProjectListScreen({
  client,
  token,
  accountEmail,
  accountPlan,
  onOpenProject,
  onSignOut,
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
      setError(err instanceof Error ? err.message : "Failed to load projects");
    } finally {
      setLoading(false);
    }
  }, [client, token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <View style={styles.flex}>
          <Text style={styles.brand}>Tokito</Text>
          <Text style={styles.sub}>
            {accountEmail} · {accountPlan}
          </Text>
        </View>
        <Pressable onPress={onSignOut}>
          <Text style={styles.link}>Sign out</Text>
        </Pressable>
      </View>

      <View style={styles.row}>
        <Text style={styles.h2}>Projects</Text>
        <Pressable onPress={() => void reload()}>
          <Text style={styles.link}>Refresh</Text>
        </Pressable>
      </View>

      {loading ? (
        <ActivityIndicator color={colors.accent} style={{ marginTop: 24 }} />
      ) : null}
      {error !== null ? <Text style={styles.error}>{error}</Text> : null}

      {!loading && projects.length === 0 && error === null ? (
        <Text style={styles.empty}>
          No synced projects yet. On desktop: project kebab → Share to Companion.
        </Text>
      ) : null}

      <FlatList
        data={projects}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ gap: 10, paddingBottom: 40 }}
        renderItem={({ item }) => (
          <Pressable style={styles.card} onPress={() => onOpenProject(item.id)}>
            <Text style={styles.cardTitle}>{item.name}</Text>
            <Text style={styles.cardMeta}>
              {item.slug} · {item.updated_at}
            </Text>
          </Pressable>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg, paddingHorizontal: 20, paddingTop: 56 },
  header: { flexDirection: "row", alignItems: "flex-start", marginBottom: 20 },
  flex: { flex: 1 },
  brand: { color: colors.text, fontSize: 28, fontWeight: "700" },
  sub: { color: colors.textMuted, marginTop: 4, fontSize: 13 },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  h2: { color: colors.text, fontSize: 18, fontWeight: "600" },
  link: { color: colors.accent, fontSize: 14 },
  card: {
    backgroundColor: colors.surface,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: 12,
    padding: 14,
  },
  cardTitle: { color: colors.text, fontSize: 16, fontWeight: "600" },
  cardMeta: { color: colors.textMuted, marginTop: 6, fontSize: 12 },
  empty: { color: colors.textMuted, lineHeight: 20, marginTop: 8 },
  error: { color: colors.danger, marginBottom: 8 },
});
