import type { JSX } from "react";
import { useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import type { Account } from "../native/types.js";
import type { AuthService } from "../auth/AuthService.js";
import { colors } from "../theme.js";

export interface SignedInScreenProps {
  account: Account;
  auth: AuthService;
  onSignedOut: () => void;
}

export function SignedInScreen({
  account,
  auth,
  onSignedOut,
}: SignedInScreenProps): JSX.Element {
  const [busy, setBusy] = useState(false);

  async function onSignOut(): Promise<void> {
    setBusy(true);
    try {
      await auth.signOut();
      onSignedOut();
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={styles.root}>
      <Text style={styles.brand}>Tokito</Text>
      <Text style={styles.sub}>Signed in</Text>
      <View style={styles.card}>
        <Text style={styles.rowLabel}>Email</Text>
        <Text style={styles.rowValue}>{account.email}</Text>
        <Text style={[styles.rowLabel, styles.gap]}>Plan</Text>
        <Text style={styles.rowValue}>{account.plan}</Text>
      </View>
      <Text style={styles.hint}>
        Project sync, BOM review, and schematic viewing land in later milestones.
      </Text>
      <Pressable
        style={[styles.button, busy && styles.buttonDisabled]}
        disabled={busy}
        onPress={() => {
          void onSignOut();
        }}
      >
        {busy ? (
          <ActivityIndicator color={colors.text} />
        ) : (
          <Text style={styles.buttonText}>Sign out</Text>
        )}
      </Pressable>
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
    marginBottom: 12,
  },
  card: {
    backgroundColor: colors.surface,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: 12,
    padding: 16,
  },
  rowLabel: {
    color: colors.textMuted,
    fontSize: 12,
  },
  rowValue: {
    color: colors.text,
    fontSize: 17,
    marginTop: 4,
  },
  gap: {
    marginTop: 16,
  },
  hint: {
    color: colors.textMuted,
    fontSize: 13,
    lineHeight: 18,
  },
  button: {
    marginTop: 16,
    backgroundColor: colors.surface,
    borderColor: colors.border,
    borderWidth: 1,
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
});
