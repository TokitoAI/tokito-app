import type { JSX } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { AuthService } from "./auth/AuthService.js";
import type { AuthState } from "./auth/AuthService.js";
import { SecureSessionStore } from "./auth/sessionStore.js";
import { isNativeCompanionAvailable, NativeCompanionClient } from "./native/bridge.js";
import { DEFAULT_CLOUD_BASE, normalizeBaseUrl } from "./cloud.js";
import { SignInScreen } from "./screens/SignInScreen.js";
import { SignedInScreen } from "./screens/SignedInScreen.js";
import { colors } from "./theme.js";

export type AppProps = {
  /** Override cloud base (empty → production). */
  cloudBaseUrl?: string;
};

export default function App({ cloudBaseUrl = "" }: AppProps): JSX.Element {
  const baseUrl = normalizeBaseUrl(cloudBaseUrl);
  const nativeReady = isNativeCompanionAvailable();

  const auth = useMemo(() => {
    if (!nativeReady) {
      return null;
    }
    const client = new NativeCompanionClient(baseUrl);
    return new AuthService(client, new SecureSessionStore());
  }, [baseUrl, nativeReady]);

  const [booting, setBooting] = useState(true);
  const [state, setState] = useState<AuthState>({ status: "signed_out" });
  const [bootError, setBootError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (auth === null) {
      setBooting(false);
      return;
    }
    setBooting(true);
    setBootError(null);
    try {
      const next = await auth.restore();
      setState(next);
    } catch (err) {
      setBootError(err instanceof Error ? err.message : "Failed to restore session");
      setState({ status: "signed_out" });
    } finally {
      setBooting(false);
    }
  }, [auth]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  if (!nativeReady) {
    return (
      <View style={styles.root}>
        <Text style={styles.brand}>Tokito</Text>
        <Text style={styles.body}>
          Native UniFFI module is not linked in this build. Use a device/simulator
          build that includes `tokito-companion-ffi` (ubrn / staticlib). Base URL:{" "}
          {baseUrl || DEFAULT_CLOUD_BASE}
        </Text>
      </View>
    );
  }

  if (booting || auth === null) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.accent} size="large" />
      </View>
    );
  }

  if (bootError !== null && state.status === "signed_out") {
    return (
      <View style={styles.root}>
        <Text style={styles.brand}>Tokito</Text>
        <Text style={styles.error}>{bootError}</Text>
        <SignInScreen
          auth={auth}
          onSignedIn={() => {
            void refresh();
          }}
        />
      </View>
    );
  }

  if (state.status === "signed_in") {
    return (
      <SignedInScreen
        account={state.account}
        auth={auth}
        onSignedOut={() => {
          setState({ status: "signed_out" });
        }}
      />
    );
  }

  return (
    <SignInScreen
      auth={auth}
      onSignedIn={() => {
        void refresh();
      }}
    />
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
  center: {
    flex: 1,
    backgroundColor: colors.bg,
    alignItems: "center",
    justifyContent: "center",
  },
  brand: {
    color: colors.text,
    fontSize: 36,
    fontWeight: "700",
  },
  body: {
    color: colors.textMuted,
    fontSize: 15,
    lineHeight: 22,
  },
  error: {
    color: colors.danger,
    fontSize: 14,
  },
});
