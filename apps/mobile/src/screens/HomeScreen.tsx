import type { JSX } from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from "react-native";
import { Camera, CloudArrowDown, LockKey } from "phosphor-react-native";
import { TABLET_MIN_WIDTH, tokens } from "../theme";
import { BrandTile, Card, GhostButton, PrimaryButton } from "../ui/primitives";

export interface HomeScreenProps {
  cloudMessage?: string | null;
  onStartBench: () => void;
  onSignIn: () => void;
}

export function HomeScreen({
  cloudMessage = null,
  onStartBench,
  onSignIn,
}: HomeScreenProps): JSX.Element {
  const { width } = useWindowDimensions();
  const tablet = width >= TABLET_MIN_WIDTH;

  return (
    <ScrollView
      style={styles.root}
      contentContainerStyle={[styles.content, tablet && styles.contentTablet]}
    >
      <View style={[styles.shell, tablet && styles.shellTablet]}>
        <View style={styles.brandRow}>
          <BrandTile size={44} />
          <Text style={styles.brand}>Tokito</Text>
        </View>

        <View style={styles.hero}>
          <Text style={[styles.title, tablet && styles.titleTablet]}>
            Work on hardware where it is.
          </Text>
          <Text style={styles.lede}>
            Inspect a board, talk through measurements, and keep guidance on your device.
          </Text>
        </View>

        <Card style={styles.benchCard}>
          <View style={styles.cardIcon}>
            <Camera size={24} color={tokens.accent} weight="regular" />
          </View>
          <View style={styles.cardCopy}>
            <Text style={styles.cardTitle}>Start a bench session</Text>
            <Text style={styles.cardBody}>
              Camera, voice, and local models run natively. No account or network is required.
            </Text>
          </View>
          <PrimaryButton label="Start session" onPress={onStartBench} style={styles.primary} />
        </Card>

        <View style={styles.cloudSection}>
          <View style={styles.cloudHeading}>
            <CloudArrowDown size={20} color={tokens.text2} weight="regular" />
            <Text style={styles.cloudTitle}>Cloud projects</Text>
          </View>
          <Text style={styles.cloudBody}>
            Sign in to review synced projects and begin a bench session with project context.
          </Text>
          {cloudMessage !== null ? (
            <Text accessibilityRole="alert" style={styles.cloudMessage}>{cloudMessage}</Text>
          ) : null}
          <GhostButton label="Sign in to Tokito Cloud" onPress={onSignIn} />
        </View>

        <View style={styles.privacyRow}>
          <LockKey size={15} color={tokens.text3} weight="regular" />
          <Text style={styles.privacyText}>Raw camera and microphone data stay on this device.</Text>
        </View>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  content: { flexGrow: 1, paddingHorizontal: tokens.space5, paddingVertical: 40 },
  contentTablet: { alignItems: "center", justifyContent: "center", paddingVertical: 64 },
  shell: { width: "100%", maxWidth: 560, alignSelf: "center", gap: 28 },
  shellTablet: { maxWidth: 760 },
  brandRow: { flexDirection: "row", alignItems: "center", gap: tokens.space3 },
  brand: { color: tokens.text, fontSize: 22, fontWeight: "700", letterSpacing: -0.4 },
  hero: { gap: tokens.space3 },
  title: { color: tokens.text, fontSize: 36, lineHeight: 40, fontWeight: "700", letterSpacing: -1.2 },
  titleTablet: { fontSize: 48, lineHeight: 52, maxWidth: 650 },
  lede: { color: tokens.text2, fontSize: 16, lineHeight: 24, maxWidth: 560 },
  benchCard: { gap: tokens.space3, padding: tokens.space5, borderColor: "rgba(8,201,170,0.38)", backgroundColor: tokens.accentSoft },
  cardIcon: { width: 44, height: 44, borderRadius: 22, alignItems: "center", justifyContent: "center", backgroundColor: tokens.brandTile },
  cardCopy: { gap: 6 },
  cardTitle: { color: tokens.text, fontSize: 18, fontWeight: "700" },
  cardBody: { color: tokens.text2, fontSize: 14, lineHeight: 21 },
  primary: { alignSelf: "flex-start", minWidth: 156, marginTop: tokens.space1 },
  cloudSection: { gap: tokens.space2, paddingHorizontal: tokens.space1 },
  cloudHeading: { flexDirection: "row", alignItems: "center", gap: tokens.space2 },
  cloudTitle: { color: tokens.text, fontSize: 15, fontWeight: "600" },
  cloudBody: { color: tokens.text2, fontSize: 13, lineHeight: 19, maxWidth: 560 },
  cloudMessage: { color: tokens.warning, fontSize: 12, lineHeight: 17 },
  privacyRow: { flexDirection: "row", alignItems: "center", gap: tokens.space2 },
  privacyText: { color: tokens.text3, fontSize: 11, flex: 1 },
});
