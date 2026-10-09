import type { JSX } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  AppState,
  Alert,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider, SafeAreaView, initialWindowMetrics } from "react-native-safe-area-context";
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
import { LiveSchematicViewerScreen } from "./screens/LiveSchematicViewerScreen";
import { HomeScreen } from "./screens/HomeScreen";
import { ModelManagerScreen } from "./screens/ModelManagerScreen";
import { SignInScreen } from "./screens/SignInScreen";
import { TokitoPhysical } from "../modules/tokito-physical/src";
import { applySystemChrome } from "./systemChrome";
import { RealtimeWorkspaceProvider } from "./sync/RealtimeWorkspace";
import { retryableStartup } from "./sync/startupRetry";
import { TABLET_MIN_WIDTH, tokens } from "./theme";
import { BrandTile } from "./ui/primitives";
import { getTokitoCompanionFfi } from "../modules/tokito-companion-ffi/src";

type Route =
  | { name: "projects" }
  | { name: "models" }
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

export default function App(props: AppProps): JSX.Element {
  return (
    <SafeAreaProvider initialMetrics={initialWindowMetrics}>
      <SafeAreaView style={styles.root}>
        <AppContent {...props} />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function AppContent({
  cloudBaseUrl = defaultCloudBaseUrl(),
  useMemoryStore = false,
}: AppProps): JSX.Element {
  const { width, height } = useWindowDimensions();
  const tablet = width >= TABLET_MIN_WIDTH && height >= 600;
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
  const [signedOutView, setSignedOutView] = useState<"home" | "sign_in" | "models">("home");
  const [bootError, setBootError] = useState<string | null>(null);
  const [restoreBlocked, setRestoreBlocked] = useState(false);
  const [retryRestore, setRetryRestore] = useState(false);
  const restoreInFlight = useRef(false);
  useEffect(() => {
    const listener = getTokitoCompanionFfi().addListener("onAuthorizationRevoked", () => {
      setState({ status: "signed_out" }); setSignedOutView("sign_in");
      setRetryRestore(false); setBootError("Your session expired. Sign in again to continue.");
      void auth.signOut();
    });
    return () => listener.remove();
  }, [auth]);

  const refresh = useCallback(async () => {
    if (restoreInFlight.current) return;
    restoreInFlight.current = true;
    setBooting(true);
    setBootError(null);
    setRestoreBlocked(false);
    setRetryRestore(false);
    try {
      const next = await auth.restore();
      setState(next);
      if (next.status === "signed_out") {
        setRoute({ name: "projects" });
        setSignedOutView("home");
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
        setRetryRestore(retryableStartup(err));
      }
    } finally {
      restoreInFlight.current = false;
      setBooting(false);
    }
  }, [auth]);

  const startBench = useCallback(async (projectId?: string, designId?: string) => {
    try {
      await TokitoPhysical.openSession({
        ...(projectId === undefined ? {} : { projectId }),
        ...(designId === undefined ? {} : { designId }),
      });
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : "Physical session is unavailable on this device.";
      Alert.alert("Could not start session", message);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!retryRestore || booting || signedOutView === "sign_in") return;
    const retry = (): void => { void refresh(); };
    const timer = setTimeout(retry, 30_000);
    const foreground = AppState.addEventListener("change", next => { if (next === "active") retry(); });
    return () => { clearTimeout(timer); foreground.remove(); };
  }, [retryRestore, booting, signedOutView, refresh]);

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

  if (state.status === "signed_out") {
    return (
      <View style={styles.root}>
        <StatusBar style="light" />
        {signedOutView === "models" ? (
          <ModelManagerScreen onBack={() => setSignedOutView("home")} />
        ) : signedOutView === "home" ? (
          <HomeScreen
            cloudMessage={restoreBlocked ? bootError ?? "Tokito Cloud is unavailable." : null}
            onStartBench={() => void startBench()}
            onManageModels={() => setSignedOutView("models")}
            onSignIn={() => {
              void (async () => {
                if (restoreBlocked) await auth.signOut();
                setRestoreBlocked(false);
                setBootError(null);
                setSignedOutView("sign_in");
              })();
            }}
          />
        ) : (
          <SignInScreen
            auth={auth}
            onBack={() => setSignedOutView("home")}
            onSignedIn={() => {
              void refresh();
            }}
          />
        )}
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

  if (route.name === "models") {
    return (
      <RealtimeWorkspaceProvider client={client} token={token}>
      <View style={styles.root}>
        <StatusBar style="light" />
        <ModelManagerScreen onBack={() => setRoute({ name: "projects" })} />
      </View>
      </RealtimeWorkspaceProvider>
    );
  }

  if (route.name === "live_schematic") {
    return (
      <RealtimeWorkspaceProvider client={client} token={token}>
        <StatusBar style="light" />
        <LiveSchematicViewerScreen
          client={client}
          token={token}
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
    const masterWidth = Math.min(330, Math.max(280, Math.floor(width * 0.28)));
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
            onStartBench={() => void startBench()}
            onManageModels={() => setRoute({ name: "models" })}
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
              onStartBench={(designId) => void startBench(detailId, designId ?? undefined)}
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
          onStartBench={(designId) => void startBench(route.id, designId ?? undefined)}
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
        onStartBench={() => void startBench()}
        onManageModels={() => setRoute({ name: "models" })}
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
