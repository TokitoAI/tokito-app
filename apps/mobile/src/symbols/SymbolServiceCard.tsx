import { useState, type JSX } from "react";
import { Text } from "react-native";
import { Card, ErrorText, Field, GhostButton, Muted, PrimaryButton } from "../ui/primitives";
import { tokens } from "../theme";
import { removeSymbolService, saveSymbolService } from "./serviceSettings";

export function SymbolServiceCard(): JSX.Element {
  const [baseUrl, setBaseUrl] = useState("https://dsvire.tokito.dev");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const configure = async (remove = false) => {
    setBusy(true);
    setError(null);
    try {
      if (remove) await removeSymbolService();
      else await saveSymbolService({ baseUrl, token });
      setSaved(!remove);
      setToken("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not save symbol service settings.");
    } finally { setBusy(false); }
  };
  return (
    <Card>
      <Text style={{ color: tokens.text, fontSize: 18, fontWeight: "700" }}>Missing board symbols</Text>
      <Muted>Optional datasheet-backed drafts for a reviewed board context. Each scan action asks before sending the board context; camera and audio stay on-device.</Muted>
      <Field label="Symbol service address" value={baseUrl} onChangeText={setBaseUrl} autoCapitalize="none" keyboardType="url" editable={!busy} />
      <Field label="Tenant API key" value={token} onChangeText={setToken} secureTextEntry autoCapitalize="none" autoCorrect={false} editable={!busy} />
      {error !== null ? <ErrorText>{error}</ErrorText> : null}
      {saved ? <Muted>Settings saved. The service and board context are checked when you request drafts.</Muted> : null}
      <PrimaryButton label="Save symbol service" busy={busy} disabled={!token} onPress={() => void configure()} />
      <GhostButton label="Disable symbol service" disabled={busy} onPress={() => void configure(true)} />
    </Card>
  );
}
