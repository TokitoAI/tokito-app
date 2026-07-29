import type { JSX } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import Constants from "expo-constants";
import { HttpCloudClient } from "./api/cloudHttp.js";
import { AuthService } from "./auth/AuthService.js";
import type { AuthState } from "./auth/AuthService.js";
import { SecureSessionStore } from "./auth/secureSessionStore.js";
import { MemorySessionStore } from "./auth/sessionStore.js";
import { normalizeBaseUrl } from "./cloud.js";
import { CompanionError } from "./native/types.js";
import { ProjectDetailScreen } from "./screens/ProjectDetailScreen.js";
import { ProjectListScreen } from "./screens/ProjectListScreen.js";
import { SchematicViewerScreen } from "./screens/SchematicViewerScreen.js";
import { SignInScreen } from "./screens/SignInScreen.js";
import { colors } from "./theme.js";

type Route =
  | { name: "projects" }
  | { name: "project"; id: string }
  | { name: "svg"; projectId: string; artifact: string };

export type AppProps = {
  cloudBaseUrl?: string;
  /** Force memory session store (Node / tests). */
  useMemoryStore?: boolean;
};

function defaultCloudBaseUrl(): string {
  const extra = Constants.expoConfig?.extra as { cloudBaseUrl?: string } | undefined;
  return extra?.cloudBaseUrl ?? "";
}

export default function App({
  cloudBaseUrl = defaultCloudBaseUrl(),
  useMemoryStore = false,
}: AppProps): JSX.Element {
  const baseUrl = normalizeBaseUrl(cloudBaseUrl);
  const client = useMemo(() => new HttpCloudClient(baseUrl), [baseUrl]);
  const auth = useMemo(() => {
    const store = useMemoryStore ? new MemorySessionStore() : new SecureSessionStore();
    return new AuthService(client, store);
  }, [client, useMemoryStore]);

  const [booting, setBooting] = useState(true);
  const [state, setState] = useState<AuthState>({ status: "signed_out" });
  const [route, setRoute] = useState<Route>({ name: "projects" });
  const [bootError, setBootError] = useState<string | null>(null);
  /** Session still on disk but restore failed (network) — offer retry, don't wipe. */
  const [restoreBlocked, setRestoreBlocked] = useState(false);

  const refresh = useCallback(async () => {
    setBooting(true);
    setBootError(null);
    setRestoreBlocked(false);
    try {
      const next = await auth.restore();
      setState(next);
      if (next.status === "signed_out") {
        setRoute({ name: "projects" });
      }
    } catch (err) {
      const message =
        err instanceof CompanionError
          ? err.message
          : err instanceof Error
            ? err.message
            : "Failed to restore session";
      setBootError(message);
      // Keep signed_out UI only when we know the session is gone; otherwise retry.
      if (err instanceof CompanionError && err.kind === "Unauthorized") {
        setState({ status: "signed_out" });
        setRestoreBlocked(false);
      } else {
        setRestoreBlocked(true);
      }
    } finally {
      setBooting(false);
    }
  }, [auth]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  if (booting) {
    return (
      <View style={styles.center}>
        <StatusBar style="light" />
        <ActivityIndicator color={colors.accent} size="large" />
      </View>
    );
  }

  if (restoreBlocked) {
    return (
      <View style={styles.centerPad}>
        <StatusBar style="light" />
        <Text style={styles.brand}>Tokito</Text>
        <Text style={styles.error}>{bootError ?? "Could not reach Tokito Cloud"}</Text>
        <Pressable style={styles.button} onPress={() => void refresh()}>
          <Text style={styles.buttonText}>Retry</Text>
        </Pressable>
        <Pressable
          onPress={() => {
            void (async () => {
              await auth.signOut();
              setRestoreBlocked(false);
              setState({ status: "signed_out" });
              setBootError(null);
            })();
          }}
        >
          <Text style={styles.link}>Sign in with a different account</Text>
        </Pressable>
      </View>
    );
  }

  if (state.status === "signed_out") {
    return (
      <View style={styles.root}>
        <StatusBar style="light" />
        {bootError !== null ? <Text style={styles.errorPad}>{bootError}</Text> : null}
        <SignInScreen
          auth={auth}
          onSignedIn={() => {
            void refresh();
          }}
        />
      </View>
    );
  }

  const token = state.session.token;

  if (route.name === "svg") {
    return (
      <>
        <StatusBar style="light" />
        <SchematicViewerScreen
          client={client}
          token={token}
          projectId={route.projectId}
          artifactName={route.artifact}
          onBack={() => setRoute({ name: "project", id: route.projectId })}
        />
      </>
    );
  }

  if (route.name === "project") {
    return (
      <>
        <StatusBar style="light" />
        <ProjectDetailScreen
          client={client}
          token={token}
          projectId={route.id}
          onBack={() => setRoute({ name: "projects" })}
          onOpenSvg={(artifact) =>
            setRoute({ name: "svg", projectId: route.id, artifact })
          }
        />
      </>
    );
  }

  return (
    <>
      <StatusBar style="light" />
      <ProjectListScreen
        client={client}
        token={token}
        accountEmail={state.account.email}
        accountPlan={state.account.plan}
        onOpenProject={(id) => setRoute({ name: "project", id })}
        onSignOut={() => {
          void (async () => {
            await auth.signOut();
            setState({ status: "signed_out" });
          })();
        }}
      />
    </>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  center: {
    flex: 1,
    backgroundColor: colors.bg,
    alignItems: "center",
    justifyContent: "center",
  },
  centerPad: {
    flex: 1,
    backgroundColor: colors.bg,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 24,
    gap: 12,
  },
  brand: { color: colors.text, fontSize: 32, fontWeight: "700" },
  error: { color: colors.danger, textAlign: "center", marginTop: 8 },
  errorPad: { color: colors.danger, padding: 16 },
  button: {
    marginTop: 8,
    backgroundColor: colors.accent,
    borderRadius: 10,
    paddingVertical: 12,
    paddingHorizontal: 24,
  },
  buttonText: { color: colors.text, fontWeight: "600", fontSize: 16 },
  link: { color: colors.accent, marginTop: 8, fontSize: 14 },
});
