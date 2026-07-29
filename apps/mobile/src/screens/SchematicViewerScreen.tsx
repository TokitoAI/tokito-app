import type { JSX } from "react";
import { useEffect, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import { WebView } from "react-native-webview";
import { ArrowLeft } from "phosphor-react-native";
import type { CloudClient } from "../native/bridge";
import { CompanionError } from "../native/types";
import { tokens } from "../theme";
import { ErrorText } from "../ui/primitives";

export interface SchematicViewerScreenProps {
  client: CloudClient;
  token: string;
  projectId: string;
  artifactName: string;
  onBack: () => void;
}

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

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const art = await client.getArtifactText(token, projectId, artifactName);
        if (!cancelled) setSvg(art.body);
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof CompanionError
              ? err.message
              : err instanceof Error
                ? err.message
                : "Failed to load SVG",
          );
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
      ? `<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=8"/><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'"/></head><body style="margin:0;background:${tokens.canvasBg};display:flex;justify-content:center;align-items:flex-start;min-height:100%;"><img alt="schematic" style="max-width:100%;height:auto" src="data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}"/></body></html>`
      : "";

  return (
    <View style={styles.root}>
      <View style={styles.chrome}>
        <Pressable onPress={onBack} style={styles.back}>
          <ArrowLeft size={18} color={tokens.accent} weight="regular" />
          <Text style={styles.link}>Back</Text>
        </Pressable>
        <Text style={styles.title} numberOfLines={1}>
          {artifactName}
        </Text>
      </View>
      {loading ? <ActivityIndicator color={tokens.accent} style={{ margin: 16 }} /> : null}
      {error !== null ? (
        <View style={{ padding: 16 }}>
          <ErrorText>{error}</ErrorText>
        </View>
      ) : null}
      {svg !== null ? (
        <WebView
          originWhitelist={["*"]}
          style={{ flex: 1, width, height: height - 96, backgroundColor: tokens.canvasBg }}
          scalesPageToFit
          source={{ html }}
          javaScriptEnabled={false}
          domStorageEnabled={false}
          allowFileAccess={false}
          allowFileAccessFromFileURLs={false}
          allowUniversalAccessFromFileURLs={false}
          setSupportMultipleWindows={false}
          onShouldStartLoadWithRequest={(req) => {
            // Static HTML + data: SVG only — block navigations off the page.
            const url = req.url;
            return (
              url === "about:blank" ||
              url.startsWith("data:") ||
              url.startsWith("about:srcdoc")
            );
          }}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  chrome: {
    paddingTop: 48,
    paddingHorizontal: tokens.space4,
    paddingBottom: tokens.space3,
    backgroundColor: tokens.bgChrome,
    borderBottomWidth: 1,
    borderBottomColor: tokens.border,
    gap: tokens.space2,
  },
  back: { flexDirection: "row", alignItems: "center", gap: tokens.space2 },
  link: { color: tokens.accent, fontWeight: "500" },
  title: { color: tokens.text, fontSize: 16, fontWeight: "600" },
});
