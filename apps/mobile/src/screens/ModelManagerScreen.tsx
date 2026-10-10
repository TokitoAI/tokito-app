import type { JSX } from "react";
import { useCallback, useEffect, useState } from "react";
import { Alert, ScrollView, StyleSheet, Text, View } from "react-native";
import * as DocumentPicker from "expo-document-picker";
import { ArrowLeft, DownloadSimple, Rewind } from "phosphor-react-native";
import {
  TokitoPhysical,
  type ModelBundleStatus,
  type ModelCapability,
} from "../../modules/tokito-physical/src";
import { tokens } from "../theme";
import { Card, ErrorText, GhostButton, Muted, PrimaryButton } from "../ui/primitives";
import { modelBundleErrorMessage as message } from "../models/modelBundleCopy";

export function ModelManagerScreen({ onBack }: { onBack: () => void }): JSX.Element {
  const [bundles, setBundles] = useState<ModelBundleStatus[]>([]);
  const [busy, setBusy] = useState<ModelCapability | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(true);

  const refresh = useCallback(async () => {
    setChecking(true);
    try { setBundles(await TokitoPhysical.modelBundles()); }
    finally { setChecking(false); }
  }, []);

  useEffect(() => {
    void refresh().catch((cause: unknown) => setError(message(cause)));
  }, [refresh]);

  const importBundle = useCallback(async (capability: ModelCapability) => {
    if (busy !== null) return;
    setError(null);
    setBusy(capability);
    try {
      const selection = await DocumentPicker.getDocumentAsync({
        type: "application/zip",
        copyToCacheDirectory: false,
        multiple: false,
      });
      if (selection.canceled) return;
      const installed = await TokitoPhysical.installModelBundle(capability, selection.assets[0].uri);
      await refresh();
      Alert.alert("Model installed", `${installed.modelId} ${installed.modelRevision} is now active.`);
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(null);
    }
  }, [busy, refresh]);

  const rollback = useCallback((capability: ModelCapability) => {
    Alert.alert(
      "Restore previous model?",
      "Tokito will swap the active model with the last verified version.",
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Restore",
          onPress: () => {
            setBusy(capability);
            setError(null);
            void TokitoPhysical.rollbackModelBundle(capability)
              .then(refresh)
              .catch((cause: unknown) => setError(message(cause)))
              .finally(() => setBusy(null));
          },
        },
      ],
    );
  }, [refresh]);

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <View style={styles.headerSide}>
          <GhostButton label="Back" onPress={onBack} disabled={busy !== null} icon={<ArrowLeft size={18} color={tokens.accent} />} />
        </View>
        <Text accessibilityRole="header" style={styles.title}>Local models</Text>
        <View style={styles.headerSide} />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.lede}>
          Import a Tokito-signed bundle from this device. Verification finishes before the active model changes.
        </Text>
        {error === null ? null : <ErrorText>{error}</ErrorText>}
        {(["dialogue", "voice", "vision", "embedding"] as const).map((capability) => {
          const bundle = bundles.find((candidate) => candidate.capability === capability);
          const installed = bundle?.state === "ready";
          const rejected = bundle?.state === "rejected";
          const stateLabel = checking ? "Checking" : installed ? "Installed" : rejected ? "Rejected" : "Missing";
          const label = { dialogue: "Conversation", voice: "Speech", vision: "Board vision", embedding: "Knowledge search" }[capability];
          return (
            <Card key={capability}>
              <View style={styles.cardHeader}>
                <View style={styles.flex}>
                  <Text style={styles.cardTitle}>{label}</Text>
                  {installed ? (
                    <Text style={styles.modelIdentity} numberOfLines={2}>
                      {bundle.modelId} · {bundle.modelRevision}
                    </Text>
                  ) : (
                    <Muted>{checking ? "Verifying the installed bundle…" : rejected
                      ? bundle.rejection === "license" ? "This bundle’s license is not allowed in this build." : "The installed bundle failed verification."
                      : "No verified model installed"}</Muted>
                  )}
                </View>
                <View
                  accessibilityLabel={stateLabel}
                  style={[styles.status, installed ? styles.ready : styles.missing]}
                >
                  <Text style={[styles.statusText, installed && styles.readyText]}>
                    {stateLabel}
                  </Text>
                </View>
              </View>
              <PrimaryButton
                label={installed ? "Replace bundle" : "Import bundle"}
                busy={busy === capability}
                disabled={checking || (busy !== null && busy !== capability)}
                onPress={() => void importBundle(capability)}
                icon={<DownloadSimple size={18} color={tokens.accentInk} />}
                style={styles.primaryAction}
              />
              <GhostButton
                label="Restore previous version"
                disabled={checking || busy !== null}
                onPress={() => rollback(capability)}
                icon={<Rewind size={17} color={tokens.accent} />}
              />
            </Card>
          );
        })}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  header: {
    minHeight: 72,
    paddingTop: 20,
    paddingHorizontal: tokens.space4,
    borderBottomWidth: 1,
    borderBottomColor: tokens.borderSoft,
    flexDirection: "row",
    alignItems: "center",
  },
  title: { color: tokens.text, fontSize: 18, fontWeight: "600", textAlign: "center" },
  headerSide: { width: 88 },
  content: { width: "100%", maxWidth: 680, alignSelf: "center", padding: tokens.space5, gap: tokens.space4 },
  lede: { color: tokens.text2, fontSize: 14, lineHeight: 21 },
  cardHeader: { flexDirection: "row", alignItems: "flex-start", gap: tokens.space3 },
  flex: { flex: 1 },
  cardTitle: { color: tokens.text, fontSize: 17, fontWeight: "600", marginBottom: 5 },
  modelIdentity: { color: tokens.text2, fontSize: 13, lineHeight: 18 },
  status: { borderRadius: 999, paddingHorizontal: 9, paddingVertical: 5, backgroundColor: tokens.cardHover },
  ready: { backgroundColor: tokens.accentSoft },
  missing: { backgroundColor: tokens.cardHover },
  statusText: { color: tokens.text2, fontSize: 11, fontWeight: "600" },
  readyText: { color: tokens.accent },
  primaryAction: { marginTop: tokens.space4, marginBottom: tokens.space1 },
});
