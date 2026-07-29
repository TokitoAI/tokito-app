import type { JSX } from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { WebView, type WebViewMessageEvent } from "react-native-webview";
import { ArrowLeft } from "phosphor-react-native";
import { useRealtimeWorkspace } from "../sync/RealtimeWorkspace";
import { tokens } from "../theme";
import { ErrorText } from "../ui/primitives";

type Tool = "select" | "wire" | "bus" | "label" | "hierarchical" | "power" | "junction" | "no_connect" | "text";

export function LiveSchematicEditorScreen({
  projectId,
  designId,
  onBack,
}: {
  projectId: string;
  designId: string;
  onBack: () => void;
}): JSX.Element {
  const realtime = useRealtimeWorkspace();
  const webview = useRef<WebView>(null);
  const resource = realtime.getResource(projectId, designId, "schematic");
  const document = resource?.payload as Record<string, unknown> | undefined;
  const [initialDocument, setInitialDocument] = useState<Record<string, unknown> | undefined>();
  const lastLocal = useRef<string | null>(null);
  const ready = useRef(false);
  const [tool, setTool] = useState<Tool>("select");
  const [annotation, setAnnotation] = useState("NET");
  const sheets = Array.isArray(document?.sheets)
    ? (document.sheets as Array<{ id?: string; name?: string }>)
    : [];
  const [sheetId, setSheetId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (initialDocument === undefined && document !== undefined) setInitialDocument(document);
  }, [document, initialDocument]);
  useEffect(() => {
    if (!ready.current || document === undefined) return;
    const encoded = JSON.stringify(document);
    if (encoded !== lastLocal.current) command({ type: "replace", document });
  }, [document]);

  const html = useMemo(() => initialDocument ? editorHtml(initialDocument) : "", [initialDocument]);

  function command(value: Record<string, unknown>): void {
    webview.current?.postMessage(JSON.stringify(value));
  }

  function selectTool(next: Tool): void {
    setTool(next);
    command({ type: "tool", tool: next, text: annotation });
  }

  async function onMessage(event: WebViewMessageEvent): Promise<void> {
    try {
      const message = JSON.parse(event.nativeEvent.data) as {
        type: string;
        document?: unknown;
        message?: string;
      };
      if (message.type === "change" && message.document !== undefined) {
        lastLocal.current = JSON.stringify(message.document);
        await realtime.update(projectId, designId, "schematic", message.document);
      } else if (message.type === "error") {
        setError(message.message ?? "Schematic editor error");
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  if (initialDocument === undefined) {
    return (
      <View style={styles.root}>
        <Header onBack={onBack} title="Live schematic" />
        <View style={styles.empty}><Text style={styles.emptyText}>Waiting for the desktop schematic resource…</Text></View>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <Header onBack={onBack} title="Live schematic" />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.toolbar}>
        {(["select", "wire", "bus", "label", "hierarchical", "power", "junction", "no_connect", "text"] as Tool[]).map((item) => (
          <Pressable key={item} onPress={() => selectTool(item)} style={[styles.tool, tool === item && styles.toolActive]}>
            <Text style={[styles.toolText, tool === item && styles.toolTextActive]}>{item.replace("_", " ")}</Text>
          </Pressable>
        ))}
      </ScrollView>
      {sheets.length > 1 ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.toolbar}>
          {sheets.map((sheet, index) => {
            const id = sheet.id ?? `sheet-${index}`;
            const active = (sheetId ?? sheets[0]?.id) === id;
            return (
              <Pressable key={id} onPress={() => { setSheetId(id); command({ type: "sheet", sheetId: id }); }} style={[styles.tool, active && styles.toolActive]}>
                <Text style={[styles.toolText, active && styles.toolTextActive]}>{sheet.name ?? id}</Text>
              </Pressable>
            );
          })}
        </ScrollView>
      ) : null}
      {tool === "label" || tool === "hierarchical" || tool === "power" || tool === "text" ? (
        <TextInput
          value={annotation}
          onChangeText={(text) => { setAnnotation(text); command({ type: "text", text }); }}
          style={styles.annotation}
          placeholder="Label or text"
          placeholderTextColor={tokens.text2}
        />
      ) : null}
      <View style={styles.actions}>
        {[
          ["←", "left"], ["↑", "up"], ["↓", "down"], ["→", "right"],
          ["Undo", "undo"], ["Redo", "redo"], ["Rotate", "rotate"], ["Mirror X", "mirror_x"], ["Mirror Y", "mirror_y"], ["Delete", "delete"],
        ].map(([label, action]) => (
          <Pressable key={action} onPress={() => command({ type: "action", action })} style={styles.action}>
            <Text style={styles.actionText}>{label}</Text>
          </Pressable>
        ))}
      </View>
      {error !== null ? <View style={styles.error}><ErrorText>{error}</ErrorText></View> : null}
      <WebView
        ref={webview}
        source={{ html }}
        originWhitelist={["about:blank"]}
        style={styles.webview}
        javaScriptEnabled
        domStorageEnabled={false}
        allowFileAccess={false}
        allowFileAccessFromFileURLs={false}
        allowUniversalAccessFromFileURLs={false}
        setSupportMultipleWindows={false}
        onMessage={(event) => void onMessage(event)}
        onLoad={() => {
          ready.current = true;
          if (document !== undefined) command({ type: "replace", document });
        }}
        onShouldStartLoadWithRequest={(request) => request.url === "about:blank" || request.url.startsWith("about:srcdoc")}
      />
    </View>
  );
}

function Header({ onBack, title }: { onBack: () => void; title: string }): JSX.Element {
  return (
    <View style={styles.header}>
      <Pressable onPress={onBack} style={styles.back}>
        <ArrowLeft size={18} color={tokens.accent} />
        <Text style={styles.backText}>Back</Text>
      </Pressable>
      <Text style={styles.title}>{title}</Text>
    </View>
  );
}

function editorHtml(document: Record<string, unknown>): string {
  const encoded = JSON.stringify(document).replace(/</g, "\\u003c");
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=6,user-scalable=yes">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<style>html,body{margin:0;width:100%;height:100%;overflow:auto;background:#07101e;color:#e8ecf2;font:12px sans-serif}svg{touch-action:none;background:#0b1320}.grid{stroke:#172334;stroke-width:1}.wire{stroke:#08c9aa;stroke-width:4;fill:none}.bus{stroke:#5456f0;stroke-width:8}.symbol{fill:#101b2a;stroke:#9aa5b4;stroke-width:3}.selected{stroke:#e07820;stroke-width:6}.pin{fill:#08c9aa}.label{fill:#e8ecf2;font-size:24px}.ref{fill:#e8ecf2;font-size:24px;font-weight:700}.value{fill:#9aa5b4;font-size:19px}.junction{fill:#08c9aa}.nc{stroke:#e07820;stroke-width:4}</style></head><body><svg id="canvas" width="1600" height="1100" viewBox="0 0 1600 1100"></svg>
<script>'use strict';let doc=${encoded};let tool='select',text='NET',selected=null,start=null,drag=null,active=(doc.sheets&&doc.sheets[0]&&doc.sheets[0].id)||'root',history=[],future=[],last=JSON.stringify(doc);const svg=document.getElementById('canvas');const NS='http://www.w3.org/2000/svg';
function el(n,a,t){const e=document.createElementNS(NS,n);Object.keys(a||{}).forEach(k=>e.setAttribute(k,String(a[k])));if(t!=null)e.textContent=String(t);return e}function snap(v){return Math.round(v/20)*20}function uid(){return globalThis.crypto&&crypto.randomUUID?crypto.randomUUID():'mobile-'+Date.now()+'-'+Math.random().toString(16).slice(2)}function activeSheet(){return active}function pinPos(s,p){let x=p.offset.x,y=p.offset.y;if(s.mirror==='x')y=-y;if(s.mirror==='y')x=-x;const q=((Math.round((s.rotation||0)/90)%4)+4)%4;if(q===1)[x,y]=[-y,x];else if(q===2)[x,y]=[-x,-y];else if(q===3)[x,y]=[y,-x];return{x:s.position.x+x,y:s.position.y+y}}function nearestPin(p){let best=null,dist=26;for(const s of (doc.symbols||[]).filter(onSheet))for(const pin of s.pins||[]){const q=pinPos(s,pin),d=Math.hypot(q.x-p.x,q.y-p.y);if(d<dist){dist=d;best={point:q,anchor:{ref_des:s.ref_des,pin_name:pin.name,symbol_id:s.id}}}}return best}function syncAnchors(s){for(const w of doc.wire_segments||[]){for(const side of ['start','end']){const a=w[side+'_pin'];if(a&&a.symbol_id===s.id){const p=(s.pins||[]).find(v=>v.name===a.pin_name);if(p)w[side]=pinPos(s,p)}}}}
function onSheet(v){return !v.sheet_id||v.sheet_id===active}function render(){svg.replaceChildren();for(let x=0;x<=1600;x+=40)svg.append(el('line',{x1:x,y1:0,x2:x,y2:1100,class:'grid'}));for(let y=0;y<=1100;y+=40)svg.append(el('line',{x1:0,y1:y,x2:1600,y2:y,class:'grid'}));(doc.wire_segments||[]).filter(onSheet).forEach(w=>svg.append(el('line',{x1:w.start.x,y1:w.start.y,x2:w.end.x,y2:w.end.y,class:'wire','data-kind':'wire','data-id':w.id})));(doc.buses||[]).filter(onSheet).forEach(w=>svg.append(el('line',{x1:w.start.x,y1:w.start.y,x2:w.end.x,y2:w.end.y,class:'wire bus','data-kind':'bus','data-id':w.id})));(doc.symbols||[]).filter(onSheet).forEach(s=>{const g=el('g',{transform:'translate('+s.position.x+' '+s.position.y+') rotate('+(s.rotation||0)+')','data-kind':'symbol','data-id':s.id});g.append(el('rect',{x:-60,y:-35,width:120,height:70,rx:5,class:'symbol '+(selected===s.id?'selected':''),'pointer-events':'all'}));g.append(el('text',{x:-55,y:-48,class:'ref','pointer-events':'none'},s.ref_des));g.append(el('text',{x:-55,y:58,class:'value','pointer-events':'none'},s.value||''));(s.pins||[]).forEach(p=>g.append(el('circle',{cx:p.offset.x,cy:p.offset.y,r:5,class:'pin','pointer-events':'none'})));svg.append(g)});(doc.net_labels||[]).filter(onSheet).forEach(n=>svg.append(el('text',{x:n.position.x,y:n.position.y,class:'label','data-kind':'label','data-id':n.id},n.name)));(doc.power_symbols||[]).filter(onSheet).forEach(n=>svg.append(el('text',{x:n.position.x,y:n.position.y,class:'label','data-kind':'power','data-id':n.id},n.name)));(doc.text_items||[]).filter(onSheet).forEach(n=>svg.append(el('text',{x:n.position.x,y:n.position.y,class:'label','data-kind':'text','data-id':n.id},n.text)));(doc.junctions||[]).filter(onSheet).forEach(n=>svg.append(el('circle',{cx:n.position.x,cy:n.position.y,r:8,class:'junction','data-kind':'junction','data-id':n.id})));(doc.no_connects||[]).filter(onSheet).forEach(n=>{const g=el('g',{'data-kind':'no_connect','data-id':n.id});g.append(el('line',{x1:n.position.x-9,y1:n.position.y-9,x2:n.position.x+9,y2:n.position.y+9,class:'nc'}));g.append(el('line',{x1:n.position.x+9,y1:n.position.y-9,x2:n.position.x-9,y2:n.position.y+9,class:'nc'}));svg.append(g)})}
function point(ev){const r=svg.getBoundingClientRect();return{x:snap((ev.clientX-r.left)*1600/r.width),y:snap((ev.clientY-r.top)*1100/r.height)}}function commit(){history.push(last);if(history.length>100)history.shift();future=[];last=JSON.stringify(doc);render();window.ReactNativeWebView.postMessage(JSON.stringify({type:'change',document:doc}))}function removeById(id){const sym=(doc.symbols||[]).find(v=>v.id===id);['symbols','wire_segments','buses','net_labels','power_symbols','text_items','junctions','no_connects'].forEach(k=>{doc[k]=(doc[k]||[]).filter(v=>v.id!==id)});if(sym)doc.wire_segments=(doc.wire_segments||[]).filter(w=>(!w.start_pin||w.start_pin.symbol_id!==id)&&(!w.end_pin||w.end_pin.symbol_id!==id))}
svg.addEventListener('pointerdown',ev=>{const target=ev.target.closest('[data-id]');const p=point(ev);if(tool==='select'&&target){selected=target.getAttribute('data-id');const s=(doc.symbols||[]).find(v=>v.id===selected);if(s){drag={id:selected,dx:p.x-s.position.x,dy:p.y-s.position.y};svg.setPointerCapture(ev.pointerId)}render();return}if(tool==='wire'||tool==='bus'){const hit=tool==='wire'?nearestPin(p):null,q=hit?hit.point:p;if(!start){start={point:q,anchor:hit&&hit.anchor};return}const key=tool==='wire'?'wire_segments':'buses';doc[key]=doc[key]||[];doc[key].push({id:uid(),sheet_id:activeSheet(),start:start.point,end:q,net_name:null,net_id:null,start_pin:start.anchor||null,end_pin:(hit&&hit.anchor)||null});start=null;commit();return}const map={label:'net_labels',hierarchical:'net_labels',power:'power_symbols',junction:'junctions',no_connect:'no_connects',text:'text_items'};const key=map[tool];if(!key)return;doc[key]=doc[key]||[];let value={id:uid(),sheet_id:activeSheet(),position:p};if(tool==='label'||tool==='hierarchical')Object.assign(value,{name:text,kind:tool==='hierarchical'?'Hierarchical':'Local',orientation:'left'});if(tool==='power')value.name=text;if(tool==='text')Object.assign(value,{text:text,rotation:0});doc[key].push(value);commit()});svg.addEventListener('pointermove',ev=>{if(!drag)return;const p=point(ev),s=(doc.symbols||[]).find(v=>v.id===drag.id);if(s){s.position={x:snap(p.x-drag.dx),y:snap(p.y-drag.dy)};syncAnchors(s);render()}});svg.addEventListener('pointerup',()=>{if(drag){drag=null;commit()}});
function action(a){if(a==='undo'&&history.length){future.push(last);last=history.pop();doc=JSON.parse(last);selected=null;render();window.ReactNativeWebView.postMessage(JSON.stringify({type:'change',document:doc}));return}if(a==='redo'&&future.length){history.push(last);last=future.pop();doc=JSON.parse(last);selected=null;render();window.ReactNativeWebView.postMessage(JSON.stringify({type:'change',document:doc}));return}if(!selected)return;const collections=['symbols','net_labels','power_symbols','text_items','junctions','no_connects'];let s=null;for(const k of collections){s=(doc[k]||[]).find(v=>v.id===selected);if(s)break}if(a==='delete'){removeById(selected);selected=null;commit();return}if(!s)return;if(a==='rotate'&&'rotation' in s)s.rotation=((s.rotation||0)+90)%360;if(a==='mirror_x'&&'mirror' in s)s.mirror=s.mirror==='x'?'none':'x';if(a==='mirror_y'&&'mirror' in s)s.mirror=s.mirror==='y'?'none':'y';if(s.position){if(a==='left')s.position.x-=40;if(a==='right')s.position.x+=40;if(a==='up')s.position.y-=40;if(a==='down')s.position.y+=40}commit()}
window.addEventListener('message',ev=>{try{const m=JSON.parse(ev.data);if(m.type==='tool'){tool=m.tool;text=m.text||text;start=null}if(m.type==='text')text=m.text;if(m.type==='sheet'){active=m.sheetId;selected=null;render()}if(m.type==='action')action(m.action);if(m.type==='replace'&&m.document){doc=m.document;last=JSON.stringify(doc);history=[];future=[];selected=null;render()}}catch(e){window.ReactNativeWebView.postMessage(JSON.stringify({type:'error',message:String(e)}))}});document.addEventListener('message',ev=>window.dispatchEvent(new MessageEvent('message',{data:ev.data})));render();</script></body></html>`;
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.bg },
  header: { paddingTop: 48, paddingHorizontal: 16, paddingBottom: 10, backgroundColor: tokens.bgChrome, borderBottomWidth: 1, borderBottomColor: tokens.border },
  back: { flexDirection: "row", alignItems: "center", gap: 8 },
  backText: { color: tokens.accent, fontWeight: "600" },
  title: { color: tokens.text, fontSize: 16, fontWeight: "700", marginTop: 8 },
  toolbar: { padding: 8, gap: 6, backgroundColor: tokens.bgChrome },
  tool: { paddingHorizontal: 10, paddingVertical: 8, borderRadius: tokens.radiusSm, borderWidth: 1, borderColor: tokens.border, backgroundColor: tokens.card },
  toolActive: { borderColor: tokens.accent, backgroundColor: tokens.accentSoft },
  toolText: { color: tokens.text2, fontSize: 12, textTransform: "capitalize" },
  toolTextActive: { color: tokens.accent, fontWeight: "600" },
  annotation: { margin: 8, color: tokens.text, borderWidth: 1, borderColor: tokens.border, backgroundColor: tokens.card, borderRadius: tokens.radiusSm, paddingHorizontal: 10, paddingVertical: 8 },
  actions: { flexDirection: "row", flexWrap: "wrap", gap: 5, paddingHorizontal: 8, paddingBottom: 8, backgroundColor: tokens.bgChrome },
  action: { paddingHorizontal: 9, paddingVertical: 7, borderRadius: tokens.radiusSm, backgroundColor: tokens.card, borderWidth: 1, borderColor: tokens.border },
  actionText: { color: tokens.text, fontSize: 11 },
  error: { paddingHorizontal: 10 },
  webview: { flex: 1, backgroundColor: tokens.canvasBg },
  empty: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24 },
  emptyText: { color: tokens.text2, textAlign: "center" },
});
