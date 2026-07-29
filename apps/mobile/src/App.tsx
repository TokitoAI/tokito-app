import type { JSX } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from "react-native";
import { StatusBar } from "expo-status-bar";
import Constants from "expo-constants";
import { useFonts, Inter_400Regular, Inter_500Medium, Inter_600SemiBold, Inter_700Bold } from "@expo-google-fonts/inter";
import { createCloudClient, type CloudClient } from "./native/bridge";
import { AuthService } from "./auth/AuthService";
import type { AuthState } from "./auth/AuthService";
import { SecureSessionStore } from "./auth/secureSessionStore";
import { MemorySessionStore } from "./auth/sessionStore";
import { normalizeBaseUrl } from "./cloud";
import { CompanionError } from "./native/types";
import { ProjectDetailScreen } from "./screens/ProjectDetailScreen";
import { ProjectListScreen } from "./screens/ProjectListScreen";
import { SchematicViewerScreen } from "./screens/SchematicViewerScreen";
import { LiveSchematicEditorScreen } from "./screens/LiveSchematicEditorScreen";
import { SignInScreen } from "./screens/SignInScreen";
import { applySystemChrome } from "./systemChrome";
import { RealtimeWorkspaceProvider } from "./sync/RealtimeWorkspace";
import { TABLET_MIN_WIDTH, tokens } from "./theme";
import { BrandTile, PrimaryButton } from "./ui/primitives";

type Route =
  | { name: "projects" }
  | { name: "project"; id: string }
  | { name: "live_schematic"; projectId: string; designId: string }
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
  const { width } = useWindowDimensions();
  const tablet = width >= TABLET_MIN_WIDTH;
  const [fontsLoaded] = useFonts({
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
    Inter_700Bold,
  });
  const [fontsReady, setFontsReady] = useState(false);
  useEffect(() => {
    if (fontsLoaded) {
      setFontsReady(true);
      return;
    }
    const t = setTimeout(() => setFontsReady(true), 2500);
    return () => clearTimeout(t);
  }, [fontsLoaded]);

  useEffect(() => {
    void applySystemChrome();
  }, []);

  const baseUrl = normalizeBaseUrl(cloudBaseUrl);
  const client = useMemo((): CloudClient => createCloudClient(baseUrl), [baseUrl]);
  const auth = useMemo(() => {
    const store = useMemoryStore ? new MemorySessionStore() : new SecureSessionStore();
    return new AuthService(client, store);
  }, [client, useMemoryStore]);

  const [booting, setBooting] = useState(true);
  const [state, setState] = useState<AuthState>({ status: "signed_out" });
  const [route, setRoute] = useState<Route>({ name: "projects" });
  const [bootError, setBootError] = useState<string | null>(null);
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

  // Wait briefly for Inter; never block boot forever if fonts stall.
  if (!fontsReady || booting) {
    return (
      <View style={styles.center}>
        <StatusBar style="light" />
        <BrandTile size={56} />
        <ActivityIndicator color={tokens.accent} size="large" style={{ marginTop: 24 }} />
      </View>
    );
  }

  if (restoreBlocked) {
    return (
      <View style={styles.centerPad}>
        <StatusBar style="light" />
        <BrandTile size={48} />
        <Text style={styles.brand}>Tokito</Text>
        <Text style={styles.error}>{bootError ?? "Could not reach Tokito Cloud"}</Text>
        <PrimaryButton label="Retry" onPress={() => void refresh()} style={{ marginTop: 8, minWidth: 160 }} />
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
  const selectedId =
    route.name === "project" || route.name === "svg" || route.name === "live_schematic"
      ? route.name === "project"
        ? route.id
        : route.projectId
      : null;

  if (route.name === "live_schematic") {
    return (
      <RealtimeWorkspaceProvider client={client} token={token}>
        <StatusBar style="light" />
        <LiveSchematicEditorScreen
          projectId={route.projectId}
          designId={route.designId}
          onBack={() => setRoute({ name: "project", id: route.projectId })}
        />
      </RealtimeWorkspaceProvider>
    );
  }

  if (route.name === "svg") {
    return (
      <RealtimeWorkspaceProvider client={client} token={token}>
        <StatusBar style="light" />
        <SchematicViewerScreen
          client={client}
          token={token}
          projectId={route.projectId}
          artifactName={route.artifact}
          onBack={() => setRoute({ name: "project", id: route.projectId })}
        />
      </RealtimeWorkspaceProvider>
    );
  }

  if (tablet) {
    const detailId = route.name === "project" ? route.id : null;
    const masterWidth = Math.min(380, Math.max(300, Math.floor(width * 0.34)));
    return (
      <RealtimeWorkspaceProvider client={client} token={token}>
      <View style={styles.split}>
        <StatusBar style="light" />
        <View style={[styles.master, { width: masterWidth }]}>
          <ProjectListScreen
            client={client}
            token={token}
            accountEmail={state.account.email}
            accountPlan={state.account.plan}
            selectedId={selectedId}
            embedded
            onOpenProject={(id) => setRoute({ name: "project", id })}
            onSignOut={() => {
              void (async () => {
                await auth.signOut();
                setState({ status: "signed_out" });
              })();
            }}
          />
        </View>
        <View style={styles.detail}>
          {detailId !== null ? (
            <ProjectDetailScreen
              client={client}
              token={token}
              projectId={detailId}
              showBack={false}
              onBack={() => setRoute({ name: "projects" })}
              onOpenSvg={(artifact) =>
                setRoute({ name: "svg", projectId: detailId, artifact })
              }
              onOpenLiveSchematic={(designId) =>
                setRoute({ name: "live_schematic", projectId: detailId, designId })
              }
            />
          ) : (
            <View style={styles.emptyDetail}>
              <BrandTile size={64} />
              <Text style={styles.emptyTitle}>Select a project</Text>
              <Text style={styles.emptyBody}>
                Live projects and designs appear here automatically.
              </Text>
            </View>
          )}
        </View>
      </View>
      </RealtimeWorkspaceProvider>
    );
  }

  if (route.name === "project") {
    return (
      <RealtimeWorkspaceProvider client={client} token={token}>
        <StatusBar style="light" />
        <ProjectDetailScreen
          client={client}
          token={token}
          projectId={route.id}
          onBack={() => setRoute({ name: "projects" })}
          onOpenSvg={(artifact) =>
            setRoute({ name: "svg", projectId: route.id, artifact })
          }
          onOpenLiveSchematic={(designId) =>
            setRoute({ name: "live_schematic", projectId: route.id, designId })
          }
        />
      </RealtimeWorkspaceProvider>
    );
  }

  return (
    <RealtimeWorkspaceProvider client={client} token={token}>
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
    </RealtimeWorkspaceProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  center: {
    flex: 1,
    backgroundColor: tokens.bg,
    alignItems: "center",
    justifyContent: "center",
  },
  centerPad: {
    flex: 1,
    backgroundColor: tokens.bg,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 24,
    gap: 12,
  },
  brand: { color: tokens.text, fontSize: 28, fontWeight: "700" },
  error: { color: tokens.danger, textAlign: "center", marginTop: 8 },
  errorPad: { color: tokens.danger, padding: 16 },
  link: { color: tokens.accent, marginTop: 8, fontSize: 14 },
  split: { flex: 1, flexDirection: "row", backgroundColor: tokens.bg },
  master: {},
  detail: { flex: 1 },
  emptyDetail: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: tokens.space3,
    padding: tokens.space5,
  },
  emptyTitle: { color: tokens.text, fontSize: 20, fontWeight: "600" },
  emptyBody: { color: tokens.text2, textAlign: "center", maxWidth: 320, lineHeight: 20 },
});
