import type { JSX } from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { ArrowLeft, ArrowsOut, Circuitry, MagnifyingGlassMinus, MagnifyingGlassPlus } from "phosphor-react-native";
import { WebView } from "react-native-webview";
import type { WebView as WebViewType } from "react-native-webview";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";
import { tokens } from "../theme";
import { ErrorText, Muted } from "../ui/primitives";

type Sheet = { id: string; name?: string };
type SchematicDocument = Record<string, unknown> & { sheets?: Sheet[] };

export function LiveSchematicViewerScreen({ projectId, designId, onBack }: { projectId: string; designId: string; onBack: () => void }): JSX.Element {
  const realtime = useRealtimeWorkspace();
  const webview = useRef<WebViewType>(null);
  const resource = realtime.getResource(projectId, designId, "schematic");
  const document = resource?.payload as SchematicDocument | undefined;
  const sheets = document?.sheets ?? [{ id: "root", name: "Root" }];
  const [activeSheet, setActiveSheet] = useState(sheets[0]?.id ?? "root");
  const html = useMemo(() => document === undefined ? "" : viewerHtml(document, activeSheet), [document, activeSheet]);

  useEffect(() => {
    if (!sheets.some((sheet) => sheet.id === activeSheet)) setActiveSheet(sheets[0]?.id ?? "root");
  }, [activeSheet, sheets]);

  function command(action: "in" | "out" | "fit"): void {
    webview.current?.postMessage(JSON.stringify({ action }));
  }

  return <View style={styles.root}>
    <View style={styles.header}>
      <Pressable accessibilityRole="button" accessibilityLabel="Back" onPress={onBack} style={styles.back}><ArrowLeft size={21} color={tokens.text} /></Pressable>
      <View style={styles.identity}><Text style={styles.eyebrow}>READ-ONLY LIVE VIEW</Text><Text style={styles.title}>Schematic</Text></View>
      <View style={styles.liveBadge}><View style={[styles.liveDot, realtime.status !== "live" && styles.liveDotWaiting]} /><Text style={styles.liveText}>{realtime.status === "live" ? "Live" : "Syncing"}</Text></View>
    </View>
    {sheets.length > 1 ? <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.sheetRail} contentContainerStyle={styles.sheetTabs}>{sheets.map((sheet) => <Pressable key={sheet.id} accessibilityRole="tab" accessibilityState={{ selected: sheet.id === activeSheet }} onPress={() => setActiveSheet(sheet.id)} style={[styles.sheetTab, sheet.id === activeSheet && styles.sheetTabActive]}><Text style={[styles.sheetText, sheet.id === activeSheet && styles.sheetTextActive]}>{sheet.name ?? sheet.id}</Text></Pressable>)}</ScrollView> : null}
    {realtime.error !== null ? <View style={styles.error}><ErrorText>{realtime.error}</ErrorText></View> : null}
    {document === undefined ? <View style={styles.empty}><View style={styles.emptyIcon}><Circuitry size={32} color={tokens.accent} /></View><Text style={styles.emptyTitle}>No schematic available</Text><Muted>This Cloud workspace does not currently contain a schematic.</Muted></View> : <WebView
      ref={webview}
      originWhitelist={["about:blank"]}
      source={{ html }}
      style={styles.webview}
      javaScriptEnabled
      domStorageEnabled={false}
      allowFileAccess={false}
      allowFileAccessFromFileURLs={false}
      allowUniversalAccessFromFileURLs={false}
      setSupportMultipleWindows={false}
      onShouldStartLoadWithRequest={(request) => request.url === "about:blank" || request.url.startsWith("about:srcdoc")}
    />}
    {document !== undefined ? <View style={styles.toolbar}>
      <Tool label="Zoom out" onPress={() => command("out")} icon={<MagnifyingGlassMinus size={22} color={tokens.text} />} />
      <Tool label="Fit schematic" onPress={() => command("fit")} icon={<ArrowsOut size={22} color={tokens.text} />} />
      <Tool label="Zoom in" onPress={() => command("in")} icon={<MagnifyingGlassPlus size={22} color={tokens.text} />} />
    </View> : null}
  </View>;
}

function Tool({ label, onPress, icon }: { label: string; onPress: () => void; icon: JSX.Element }): JSX.Element {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={({ pressed }) => [styles.tool, pressed && styles.pressed]}>{icon}</Pressable>;
}

function viewerHtml(document: SchematicDocument, activeSheet: string): string {
  const encoded = JSON.stringify(document).replace(/</g, "\\u003c");
  const sheet = JSON.stringify(activeSheet);
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#0c0d10}svg{width:100%;height:100%;display:block;touch-action:none;background:#0b1018}.grid{stroke:#18202b;stroke-width:1}.wire{stroke:#08c9aa;stroke-width:4;fill:none;stroke-linecap:round}.bus{stroke:#5456f0;stroke-width:8}.symbol{fill:#101924;stroke:#a8b0bd;stroke-width:3}.pin{fill:#08c9aa}.label{fill:#ecedf0;font:24px sans-serif}.ref{fill:#ecedf0;font:bold 24px sans-serif}.value{fill:#9a9da7;font:19px sans-serif}.junction{fill:#08c9aa}.nc{stroke:#e07820;stroke-width:4}</style></head><body><svg id="canvas" viewBox="0 0 1600 1100" preserveAspectRatio="xMidYMid meet" aria-label="Read-only schematic"></svg><script>'use strict';const doc=${encoded},active=${sheet},svg=document.getElementById('canvas'),NS='http://www.w3.org/2000/svg';let box={x:0,y:0,w:1600,h:1100},drag=null,touches=new Map(),pinch=null;
function el(n,a,t){const e=document.createElementNS(NS,n);Object.keys(a||{}).forEach(k=>e.setAttribute(k,String(a[k])));if(t!=null)e.textContent=String(t);return e}function onSheet(v){return !v.sheet_id||v.sheet_id===active}function apply(){svg.setAttribute('viewBox',box.x+' '+box.y+' '+box.w+' '+box.h)}function zoom(f,cx=box.x+box.w/2,cy=box.y+box.h/2){const nw=Math.max(180,Math.min(5000,box.w*f)),nh=nw*1100/1600;box={x:cx-(cx-box.x)*nw/box.w,y:cy-(cy-box.y)*nh/box.h,w:nw,h:nh};apply()}function fit(){box={x:0,y:0,w:1600,h:1100};apply()}function pt(e){const r=svg.getBoundingClientRect();return{x:box.x+(e.clientX-r.left)*box.w/r.width,y:box.y+(e.clientY-r.top)*box.h/r.height}}
for(let x=0;x<=1600;x+=40)svg.append(el('line',{x1:x,y1:0,x2:x,y2:1100,class:'grid'}));for(let y=0;y<=1100;y+=40)svg.append(el('line',{x1:0,y1:y,x2:1600,y2:y,class:'grid'}));(doc.wire_segments||[]).filter(onSheet).forEach(w=>svg.append(el('line',{x1:w.start.x,y1:w.start.y,x2:w.end.x,y2:w.end.y,class:'wire'})));(doc.buses||[]).filter(onSheet).forEach(w=>svg.append(el('line',{x1:w.start.x,y1:w.start.y,x2:w.end.x,y2:w.end.y,class:'wire bus'})));(doc.symbols||[]).filter(onSheet).forEach(s=>{const g=el('g',{transform:'translate('+s.position.x+' '+s.position.y+') rotate('+(s.rotation||0)+')'});g.append(el('rect',{x:-60,y:-35,width:120,height:70,rx:5,class:'symbol'}));g.append(el('text',{x:-55,y:-48,class:'ref'},s.ref_des));g.append(el('text',{x:-55,y:58,class:'value'},s.value||''));(s.pins||[]).forEach(p=>g.append(el('circle',{cx:p.offset.x,cy:p.offset.y,r:5,class:'pin'})));svg.append(g)});(doc.net_labels||[]).filter(onSheet).forEach(n=>svg.append(el('text',{x:n.position.x,y:n.position.y,class:'label'},n.name)));(doc.power_symbols||[]).filter(onSheet).forEach(n=>svg.append(el('text',{x:n.position.x,y:n.position.y,class:'label'},n.name)));(doc.text_items||[]).filter(onSheet).forEach(n=>svg.append(el('text',{x:n.position.x,y:n.position.y,class:'label'},n.text)));(doc.junctions||[]).filter(onSheet).forEach(n=>svg.append(el('circle',{cx:n.position.x,cy:n.position.y,r:8,class:'junction'})));(doc.no_connects||[]).filter(onSheet).forEach(n=>{svg.append(el('line',{x1:n.position.x-9,y1:n.position.y-9,x2:n.position.x+9,y2:n.position.y+9,class:'nc'}));svg.append(el('line',{x1:n.position.x+9,y1:n.position.y-9,x2:n.position.x-9,y2:n.position.y+9,class:'nc'}))});
svg.addEventListener('pointerdown',e=>{svg.setPointerCapture(e.pointerId);touches.set(e.pointerId,pt(e));if(touches.size===1)drag=pt(e);if(touches.size===2){const a=[...touches.values()];pinch={d:Math.hypot(a[0].x-a[1].x,a[0].y-a[1].y),w:box.w}}});svg.addEventListener('pointermove',e=>{if(!touches.has(e.pointerId))return;const before=touches.get(e.pointerId);touches.set(e.pointerId,pt(e));if(touches.size===1&&drag&&before){const now=pt(e);box.x-=now.x-before.x;box.y-=now.y-before.y;apply()}else if(touches.size===2&&pinch){const a=[...touches.values()],d=Math.hypot(a[0].x-a[1].x,a[0].y-a[1].y);zoom((pinch.w/d)/box.w)}});function up(e){touches.delete(e.pointerId);drag=touches.size===1?[...touches.values()][0]:null;pinch=null}svg.addEventListener('pointerup',up);svg.addEventListener('pointercancel',up);svg.addEventListener('wheel',e=>{e.preventDefault();const p=pt(e);zoom(e.deltaY>0?1.15:.87,p.x,p.y)},{passive:false});window.addEventListener('message',e=>{try{const m=JSON.parse(e.data);if(m.action==='in')zoom(.8);if(m.action==='out')zoom(1.25);if(m.action==='fit')fit()}catch{}});document.addEventListener('message',e=>window.dispatchEvent(new MessageEvent('message',{data:e.data})));apply();</script></body></html>`;
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  header: { minHeight: 94, paddingTop: 36, paddingHorizontal: 14, paddingBottom: 10, flexDirection: "row", alignItems: "center", gap: 11, backgroundColor: tokens.bgChrome, borderBottomWidth: 1, borderBottomColor: tokens.border },
  back: { width: 44, height: 44, borderRadius: 22, alignItems: "center", justifyContent: "center", backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.border },
  identity: { flex: 1 }, eyebrow: { color: tokens.text3, fontSize: 9, fontWeight: "700", letterSpacing: 1.1 }, title: { color: tokens.text, fontSize: 20, fontWeight: "700" },
  liveBadge: { height: 32, paddingHorizontal: 10, borderRadius: 16, flexDirection: "row", alignItems: "center", gap: 6, backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.border }, liveDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: tokens.success }, liveDotWaiting: { backgroundColor: tokens.warning }, liveText: { color: tokens.text2, fontSize: 11, fontWeight: "600" },
  sheetRail: { flexGrow: 0, maxHeight: 50, backgroundColor: tokens.bgChrome, borderBottomWidth: 1, borderBottomColor: tokens.border }, sheetTabs: { padding: 7, gap: 6 }, sheetTab: { minHeight: 36, justifyContent: "center", paddingHorizontal: 14, borderRadius: 18, borderWidth: 1, borderColor: tokens.border }, sheetTabActive: { borderColor: tokens.accent, backgroundColor: tokens.accentSoft }, sheetText: { color: tokens.text2, fontSize: 12 }, sheetTextActive: { color: tokens.accent, fontWeight: "700" },
  webview: { flex: 1, backgroundColor: tokens.canvasBg }, toolbar: { position: "absolute", bottom: 20, alignSelf: "center", height: 58, padding: 6, flexDirection: "row", gap: 6, borderRadius: 29, borderWidth: 1, borderColor: tokens.borderStrong, backgroundColor: tokens.bgChrome }, tool: { width: 46, height: 46, borderRadius: 23, alignItems: "center", justifyContent: "center", backgroundColor: tokens.card }, pressed: { opacity: 0.65 },
  error: { padding: 10 }, empty: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24, gap: 12 }, emptyIcon: { width: 64, height: 64, borderRadius: 32, alignItems: "center", justifyContent: "center", backgroundColor: tokens.accentSoft }, emptyTitle: { color: tokens.text, fontSize: 19, fontWeight: "700" },
});
