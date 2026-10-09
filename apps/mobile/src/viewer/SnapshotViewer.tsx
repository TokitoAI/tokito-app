import type { JSX, ReactNode } from "react";
import { useMemo, useRef, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { ArrowsOut, Minus, Plus } from "phosphor-react-native";
import { WebView } from "react-native-webview";
import { tokens } from "../theme";
import { snapshotHtml } from "./snapshotRuntime";

export function SnapshotViewer({ svg }: { svg: string }): JSX.Element {
  const view = useRef<WebView>(null);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const [percent, setPercent] = useState(100);
  const html = useMemo(() => { try { return snapshotHtml(svg); } catch { return null; } }, [svg]);
  function command(action: string): void { view.current?.postMessage(JSON.stringify({ action })); }
  return <View style={styles.root}>
    {html !== null && !failed ? <WebView ref={view} source={{ html }} style={styles.canvas}
      originWhitelist={["about:blank"]} javaScriptEnabled domStorageEnabled={false}
      allowFileAccess={false} allowFileAccessFromFileURLs={false} allowUniversalAccessFromFileURLs={false}
      mixedContentMode="never" setSupportMultipleWindows={false} scrollEnabled={false} bounces={false}
      onShouldStartLoadWithRequest={(request) => request.url === "about:blank" || request.url === "about:srcdoc"}
      onError={() => setFailed(true)} onContentProcessDidTerminate={() => setFailed(true)}
      onMessage={(event) => { try { const data = JSON.parse(event.nativeEvent.data) as { type?: string; percent?: number }; if (data.type === "ready") setReady(true); if (data.type === "error") setFailed(true); if (typeof data.percent === "number" && Number.isFinite(data.percent)) setPercent(data.percent); } catch { /* Ignore invalid bridge messages. */ } }} /> : null}
    {html === null || failed ? <View style={styles.state}><Text style={styles.stateTitle}>Snapshot could not be displayed</Text><Text style={styles.help}>Go back and reopen the drawing to try again.</Text></View> : !ready ? <View style={styles.loading}><ActivityIndicator color={tokens.accent} /><Text style={styles.help}>Opening drawing…</Text></View> : null}
    <View style={styles.controls}><Text style={styles.hint}>Pinch to zoom · drag to pan</Text><View style={styles.toolbar}>
      <Control label="Zoom out" disabled={!ready || percent <= 100 || failed} onPress={() => command("out")}><Minus size={21} color={tokens.text} /></Control>
      <Text accessibilityLiveRegion="polite" accessibilityLabel={`Zoom ${percent} percent of fitted drawing`} style={styles.percent}>{percent}%</Text>
      <Control label="Zoom in" disabled={!ready || percent >= 1600 || failed} onPress={() => command("in")}><Plus size={21} color={tokens.text} /></Control>
      <View style={styles.divider} /><Control label="Fit drawing" disabled={!ready || failed} onPress={() => command("fit")}><ArrowsOut size={20} color={tokens.accent} /><Text style={styles.fit}>Fit</Text></Control>
    </View></View>
  </View>;
}
function Control({ label, disabled, onPress, children }: { label: string; disabled: boolean; onPress: () => void; children: ReactNode }): JSX.Element {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled }} disabled={disabled} onPress={onPress} style={({ pressed }) => [styles.control, disabled && styles.disabled, pressed && styles.pressed]}>{children}</Pressable>;
}
const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.canvasBg }, canvas: { flex: 1, backgroundColor: tokens.canvasBg }, loading: { position: "absolute", top: 0, left: 0, right: 0, bottom: 100, alignItems: "center", justifyContent: "center", gap: 12, backgroundColor: tokens.canvasBg },
  state: { flex: 1, padding: 24, alignItems: "center", justifyContent: "center", gap: 8 }, stateTitle: { color: tokens.text, fontSize: 17, fontWeight: "600" }, help: { color: tokens.text2, fontSize: 14, textAlign: "center", lineHeight: 21 },
  controls: { padding: 12, paddingTop: 8, alignItems: "center", gap: 8, borderTopWidth: 1, borderTopColor: tokens.border, backgroundColor: tokens.bgChrome }, hint: { fontSize: 12, color: tokens.text2 }, toolbar: { flexDirection: "row", alignItems: "center", gap: 4 }, control: { minWidth: 48, height: 48, paddingHorizontal: 12, borderRadius: 12, flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 7, backgroundColor: tokens.card }, percent: { minWidth: 62, textAlign: "center", color: tokens.text, fontSize: 14, fontVariant: ["tabular-nums"] }, fit: { color: tokens.accent, fontSize: 14, fontWeight: "600" }, divider: { width: 1, height: 24, backgroundColor: tokens.borderStrong, marginHorizontal: 8 }, disabled: { opacity: 0.35 }, pressed: { backgroundColor: tokens.cardHover },
});
