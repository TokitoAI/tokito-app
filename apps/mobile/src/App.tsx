import type { JSX } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { HttpCloudClient } from "./api/cloudHttp.js";
import { AuthService } from "./auth/AuthService.js";
import type { AuthState } from "./auth/AuthService.js";
import { MemorySessionStore, SecureSessionStore } from "./auth/sessionStore.js";
import { normalizeBaseUrl } from "./cloud.js";
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

export default function App({
  cloudBaseUrl = "",
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

  const refresh = useCallback(async () => {
    setBooting(true);
    setBootError(null);
    try {
      const next = await auth.restore();
      setState(next);
      if (next.status === "signed_out") {
        setRoute({ name: "projects" });
      }
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

  if (booting) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.accent} size="large" />
      </View>
    );
  }

  if (state.status === "signed_out") {
    return (
      <View style={styles.root}>
        {bootError !== null ? <Text style={styles.error}>{bootError}</Text> : null}
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
      <SchematicViewerScreen
        client={client}
        token={token}
        projectId={route.projectId}
        artifactName={route.artifact}
        onBack={() => setRoute({ name: "project", id: route.projectId })}
      />
    );
  }

  if (route.name === "project") {
    return (
      <ProjectDetailScreen
        client={client}
        token={token}
        projectId={route.id}
        onBack={() => setRoute({ name: "projects" })}
        onOpenSvg={(artifact) =>
          setRoute({ name: "svg", projectId: route.id, artifact })
        }
      />
    );
  }

  return (
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
  error: { color: colors.danger, padding: 16 },
});
