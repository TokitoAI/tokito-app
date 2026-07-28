import type { JSX } from "react";
import { useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import type { AuthService } from "../auth/AuthService.js";
import { CompanionError } from "../native/types.js";
import { colors } from "../theme.js";

type Step = "email" | "code";

export interface SignInScreenProps {
  auth: AuthService;
  onSignedIn: () => void;
}

export function SignInScreen({ auth, onSignedIn }: SignInScreenProps): JSX.Element {
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
    <View style={styles.root}>
      <Text style={styles.brand}>Tokito</Text>
      <Text style={styles.sub}>Companion — sign in with your Tokito Cloud account</Text>

      {step === "email" ? (
        <>
          <Text style={styles.label}>Email</Text>
          <TextInput
            style={styles.input}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="email-address"
            placeholder="you@lab.dev"
            placeholderTextColor={colors.textMuted}
            value={email}
            onChangeText={setEmail}
            editable={!busy}
          />
          <Pressable
            style={[styles.button, busy && styles.buttonDisabled]}
            disabled={busy || email.trim().length === 0}
            onPress={() => {
              void onRequestCode();
            }}
          >
            {busy ? (
              <ActivityIndicator color={colors.text} />
            ) : (
              <Text style={styles.buttonText}>Send code</Text>
            )}
          </Pressable>
        </>
      ) : (
        <>
          <Text style={styles.label}>Code sent to {email.trim()}</Text>
          <TextInput
            style={styles.input}
            keyboardType="number-pad"
            placeholder="123456"
            placeholderTextColor={colors.textMuted}
            value={code}
            onChangeText={setCode}
            editable={!busy}
          />
          <Pressable
            style={[styles.button, busy && styles.buttonDisabled]}
            disabled={busy || code.trim().length === 0}
            onPress={() => {
              void onVerify();
            }}
          >
            {busy ? (
              <ActivityIndicator color={colors.text} />
            ) : (
              <Text style={styles.buttonText}>Verify</Text>
            )}
          </Pressable>
          <Pressable
            disabled={busy}
            onPress={() => {
              setStep("email");
              setCode("");
              setError(null);
            }}
          >
            <Text style={styles.link}>Use a different email</Text>
          </Pressable>
        </>
      )}

      {error !== null ? <Text style={styles.error}>{error}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.bg,
    paddingHorizontal: 24,
    paddingTop: 72,
    gap: 12,
  },
  brand: {
    color: colors.text,
    fontSize: 36,
    fontWeight: "700",
  },
  sub: {
    color: colors.textMuted,
    fontSize: 15,
    marginBottom: 24,
  },
  label: {
    color: colors.textMuted,
    fontSize: 13,
  },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    color: colors.text,
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
  },
  button: {
    marginTop: 8,
    backgroundColor: colors.accent,
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: "center",
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  buttonText: {
    color: colors.text,
    fontWeight: "600",
    fontSize: 16,
  },
  link: {
    color: colors.accent,
    marginTop: 8,
    fontSize: 14,
  },
  error: {
    color: colors.danger,
    marginTop: 12,
    fontSize: 14,
  },
});
