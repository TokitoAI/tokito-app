import type { JSX } from "react";
import { useEffect, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import type { HttpCloudClient } from "../api/cloudHttp.js";
import { colors } from "../theme.js";

export interface SchematicViewerScreenProps {
  client: HttpCloudClient;
  token: string;
  projectId: string;
  artifactName: string;
  onBack: () => void;
}

/**
 * Read-only schematic viewer — loads SVG artifact and renders via HTML webview
 * markup when available; falls back to scrollable SVG source for environments
 * without a WebView native module.
 */
export function SchematicViewerScreen({
  client,
  token,
  projectId,
  artifactName,
  onBack,
}: SchematicViewerScreenProps): JSX.Element {
  const { width, height } = useWindowDimensions();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [svg, setSvg] = useState<string | null>(null);
  const [WebViewComp, setWebViewComp] = useState<null | React.ComponentType<{
    originWhitelist: string[];
    style: object;
    scalesPageToFit?: boolean;
    source: { html: string };
  }>>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const art = await client.getArtifactText(token, projectId, artifactName);
        if (!cancelled) setSvg(art.body);
        try {
          const mod = await import("react-native-webview");
          if (!cancelled) {
            setWebViewComp(
              () =>
                mod.WebView as React.ComponentType<{
                  originWhitelist: string[];
                  style: object;
                  scalesPageToFit?: boolean;
                  source: { html: string };
                }>,
            );
          }
        } catch {
          // WebView optional — source fallback below.
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load SVG");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [client, token, projectId, artifactName]);

  const html =
    svg !== null
      ? `<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5"/></head><body style="margin:0;background:#f8f6f0;display:flex;justify-content:center;">${svg}</body></html>`
      : "";

  return (
    <View style={styles.root}>
      <Pressable onPress={onBack}>
        <Text style={styles.link}>← Back</Text>
      </Pressable>
      <Text style={styles.title}>{artifactName}</Text>
      {loading ? <ActivityIndicator color={colors.accent} /> : null}
      {error !== null ? <Text style={styles.error}>{error}</Text> : null}
      {svg !== null && WebViewComp !== null ? (
        <WebViewComp
          originWhitelist={["*"]}
          style={{ flex: 1, width, height: height - 140, backgroundColor: "#f8f6f0" }}
          scalesPageToFit
          source={{ html }}
        />
      ) : null}
      {svg !== null && WebViewComp === null ? (
        <ScrollView style={styles.fallback} contentContainerStyle={{ padding: 8 }}>
          <Text style={styles.muted}>WebView unavailable — SVG source preview</Text>
          <Text style={styles.source} selectable>
            {svg.slice(0, 8000)}
            {svg.length > 8000 ? "\n…" : ""}
          </Text>
        </ScrollView>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg, paddingTop: 56, paddingHorizontal: 12 },
  link: { color: colors.accent, marginBottom: 8, marginLeft: 8 },
  title: { color: colors.text, fontSize: 18, fontWeight: "600", marginLeft: 8, marginBottom: 8 },
  error: { color: colors.danger, marginLeft: 8 },
  fallback: { flex: 1, backgroundColor: colors.surface, borderRadius: 8 },
  muted: { color: colors.textMuted, marginBottom: 8 },
  source: { color: colors.text, fontFamily: "monospace", fontSize: 11 },
});
