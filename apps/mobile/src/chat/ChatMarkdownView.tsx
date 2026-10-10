import { memo, useMemo, type JSX, type ReactNode } from "react";
import { Alert, Linking, Platform, ScrollView, StyleSheet, Text, View } from "react-native";
import type { Token, Tokens } from "marked";
import { tokens } from "../theme";
import { chatMarkdown, safeChatLink } from "./chatMarkdown";

function inline(items: Token[] | undefined, fallback = "", depth = 0): ReactNode {
  if (!items || depth > 16) return fallback;
  return items.map((item, i) => {
    switch (item.type) {
      case "strong": return <Text key={i} style={styles.strong}>{inline((item as Tokens.Strong).tokens, item.raw, depth + 1)}</Text>;
      case "em": return <Text key={i} style={styles.em}>{inline((item as Tokens.Em).tokens, item.raw, depth + 1)}</Text>;
      case "del": return <Text key={i} style={styles.del}>{inline((item as Tokens.Del).tokens, item.raw, depth + 1)}</Text>;
      case "codespan": return <Text key={i} style={styles.inlineCode}>{(item as Tokens.Codespan).text}</Text>;
      case "link": {
        const link = item as Tokens.Link;
        return <Text key={i} accessibilityRole={safeChatLink(link.href) ? "link" : undefined} style={safeChatLink(link.href) ? styles.link : undefined} onPress={safeChatLink(link.href) ? () => { void Linking.openURL(link.href).catch(() => { Alert.alert("Link could not open", "No browser is available for this link. You can select and copy the answer instead."); }); } : undefined}>{inline(link.tokens, link.text, depth + 1)}</Text>;
      }
      // Images are captions only: model output cannot trigger tracking requests.
      case "image": return <Text key={i}>{(item as Tokens.Image).text || "[Image]"}</Text>;
      case "br": return "\n";
      default: return "text" in item && typeof item.text === "string" ? item.text : item.raw;
    }
  });
}
function blocks(items: Token[], depth = 0): ReactNode {
  if (depth > 16) return <Text style={styles.body}>{items.map(t => t.raw).join("")}</Text>;
  return items.map((item, i) => {
    switch (item.type) {
      case "space": return null;
      case "heading": { const h = item as Tokens.Heading; return <Text selectable accessibilityRole="header" key={i} style={[styles.body, styles.heading, h.depth < 3 && styles.majorHeading]}>{inline(h.tokens, h.text)}</Text>; }
      case "paragraph": case "text": { const p = item as Tokens.Paragraph; return <Text selectable key={i} style={styles.body}>{inline(p.tokens, p.text)}</Text>; }
      case "code": return <ScrollView key={i} horizontal style={styles.codeBlock} contentContainerStyle={styles.codeContent}><Text selectable style={styles.code}>{(item as Tokens.Code).text}</Text></ScrollView>;
      case "blockquote": return <View key={i} style={styles.quote}>{blocks((item as Tokens.Blockquote).tokens, depth + 1)}</View>;
      case "list": { const l = item as Tokens.List; return <View key={i} style={styles.list}>{l.items.map((entry, n) => <View key={n} style={styles.listRow}><Text style={styles.bullet}>{l.ordered ? `${Number(l.start) + n}.` : entry.task ? entry.checked ? "☑" : "☐" : "•"}</Text><View style={styles.listText}>{blocks(entry.tokens, depth + 1)}</View></View>)}</View>; }
      case "table": { const table = item as Tokens.Table; return <ScrollView horizontal key={i} style={styles.table}><View>{[table.header, ...table.rows].map((row, r) => <View key={r} style={styles.tableRow}>{row.map((cell, c) => <Text selectable key={c} style={[styles.cell, r === 0 && styles.strong]}>{inline(cell.tokens, cell.text)}</Text>)}</View>)}</View></ScrollView>; }
      case "hr": return <View key={i} style={styles.rule} />;
      // Raw HTML remains literal selectable text, never interpreted.
      default: return <Text selectable key={i} style={styles.body}>{item.raw}</Text>;
    }
  });
}
export const ChatMarkdown = memo(function ChatMarkdown({ content }: { content: string }): JSX.Element {
  const parsed = useMemo(() => chatMarkdown(content), [content]);
  return <View style={styles.document}>{blocks(parsed)}</View>;
});
const mono = Platform.OS === "ios" ? "Menlo" : "monospace";
const styles = StyleSheet.create({
  document: { gap: 10, flexShrink: 1 }, body: { color: tokens.text, fontSize: 15, lineHeight: 23 }, strong: { fontWeight: "700" }, em: { fontStyle: "italic" }, del: { textDecorationLine: "line-through" }, link: { color: tokens.accent, textDecorationLine: "underline" }, inlineCode: { fontFamily: mono, backgroundColor: tokens.bgChrome, fontSize: 14 }, heading: { fontWeight: "700", marginTop: 6 }, majorHeading: { fontSize: 18, lineHeight: 26 }, codeBlock: { backgroundColor: tokens.bgChrome, borderRadius: tokens.radiusSm }, codeContent: { padding: 12 }, code: { color: tokens.text, fontFamily: mono, fontSize: 13, lineHeight: 21 }, quote: { borderLeftWidth: 3, borderLeftColor: tokens.borderStrong, paddingLeft: 12, gap: 8 }, list: { gap: 6 }, listRow: { flexDirection: "row", gap: 8 }, bullet: { color: tokens.text2, minWidth: 22, lineHeight: 23 }, listText: { flex: 1, gap: 6 }, table: { borderWidth: 1, borderColor: tokens.border, borderRadius: tokens.radiusSm }, tableRow: { flexDirection: "row", borderBottomWidth: 1, borderBottomColor: tokens.border }, cell: { width: 180, padding: 10, color: tokens.text, fontSize: 14, lineHeight: 21 }, rule: { height: 1, backgroundColor: tokens.border, marginVertical: 6 },
});
