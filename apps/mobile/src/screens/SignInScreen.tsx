import type { JSX } from "react";
import { useState } from "react";
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from "react-native";
import { SignIn as SignInIcon, EnvelopeSimple, Key } from "phosphor-react-native";
import type { AuthService } from "../auth/AuthService";
import { CompanionError } from "../native/types";
import { TABLET_MIN_WIDTH, tokens } from "../theme";
import {
  BrandTile,
  ErrorText,
  Field,
  GhostButton,
  PrimaryButton,
} from "../ui/primitives";

type Step = "email" | "code";

export interface SignInScreenProps {
  auth: AuthService;
  onSignedIn: () => void;
  /** Active Tokito Cloud host (for footer; no hardcoded production host). */
  cloudHost: string;
}

export function SignInScreen({
  auth,
  onSignedIn,
  cloudHost,
}: SignInScreenProps): JSX.Element {
  const { width } = useWindowDimensions();
  const tablet = width >= TABLET_MIN_WIDTH;
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onRequestCode(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await auth.requestOtp(email);
      setStep("code");
    } catch (err) {
      setError(err instanceof CompanionError ? err.message : "Could not send code");
    } finally {
      setBusy(false);
    }
  }

  async function onVerify(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await auth.verifyOtp(email, code);
      onSignedIn();
    } catch (err) {
      setError(err instanceof CompanionError ? err.message : "Could not verify code");
    } finally {
      setBusy(false);
    }
  }

  return (
    <KeyboardAvoidingView
      style={styles.root}
      behavior={Platform.OS === "ios" ? "padding" : undefined}
    >
      <ScrollView
        contentContainerStyle={[
          styles.scroll,
          tablet && styles.scrollTablet,
        ]}
        keyboardShouldPersistTaps="handled"
      >
        <View style={[styles.panel, tablet && styles.panelTablet]}>
          <View style={styles.brandRow}>
            <BrandTile size={48} />
            <View style={styles.brandText}>
              <Text style={styles.brand}>Tokito</Text>
              <Text style={styles.sub}>Companion</Text>
            </View>
          </View>
          <Text style={styles.lede}>
            Sign in with your Tokito Cloud account to review synced projects,
            BOM, proposals, and schematics.
          </Text>

          {step === "email" ? (
            <View style={styles.form}>
              <Field
                label="Email"
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType="email-address"
                placeholder="Email"
                value={email}
                onChangeText={setEmail}
                editable={!busy}
              />
              <PrimaryButton
                label="Send code"
                busy={busy}
                disabled={email.trim().length === 0}
                onPress={() => {
                  void onRequestCode();
                }}
              />
              <View style={styles.hintRow}>
                <EnvelopeSimple size={16} color={tokens.text3} weight="regular" />
                <Text style={styles.hint}>We email a one-time code — same as desktop.</Text>
              </View>
            </View>
          ) : (
            <View style={styles.form}>
              <Field
                label={`Code sent to ${email.trim()}`}
                keyboardType="number-pad"
                placeholder="6-digit code"
                value={code}
                onChangeText={setCode}
                editable={!busy}
              />
              <PrimaryButton
                label="Verify"
                busy={busy}
                disabled={code.trim().length === 0}
                onPress={() => {
                  void onVerify();
                }}
              />
              <GhostButton
                label="Use a different email"
                disabled={busy}
                onPress={() => {
                  setStep("email");
                  setCode("");
                  setError(null);
                }}
              />
              <View style={styles.hintRow}>
                <Key size={16} color={tokens.text3} weight="regular" />
                <Text style={styles.hint}>Enter the 6-digit code from your inbox.</Text>
              </View>
            </View>
          )}

          {error !== null ? <ErrorText>{error}</ErrorText> : null}

          <View style={styles.footer}>
            <SignInIcon size={14} color={tokens.text3} weight="regular" />
            <Text style={styles.footerText}>{cloudHost}</Text>
          </View>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  scroll: {
    flexGrow: 1,
    justifyContent: "center",
    paddingHorizontal: tokens.space5,
    paddingVertical: 48,
  },
  scrollTablet: { alignItems: "center" },
  panel: { width: "100%", gap: tokens.space4 },
  panelTablet: {
    maxWidth: 440,
    backgroundColor: tokens.bgChrome,
    borderWidth: 1,
    borderColor: tokens.border,
    borderRadius: tokens.radiusLg,
    padding: tokens.space5,
  },
  brandRow: { flexDirection: "row", alignItems: "center", gap: tokens.space3 },
  brandText: { gap: 2 },
  brand: { color: tokens.text, fontSize: 28, fontWeight: "700", letterSpacing: -0.4 },
  sub: { color: tokens.accent, fontSize: 14, fontWeight: "600", letterSpacing: 0.4 },
  lede: { color: tokens.text2, fontSize: 15, lineHeight: 22 },
  form: { gap: tokens.space3, marginTop: tokens.space2 },
  hintRow: { flexDirection: "row", alignItems: "center", gap: tokens.space2 },
  hint: { color: tokens.text3, fontSize: 12, flex: 1, lineHeight: 16 },
  footer: {
    flexDirection: "row",
    alignItems: "center",
    gap: tokens.space2,
    marginTop: tokens.space3,
  },
  footerText: { color: tokens.text3, fontSize: 12 },
});
