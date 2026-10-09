import type { ArtifactMeta, ProjectDetail } from "../api/cloudHttp";

export function schematicSnapshots(project: ProjectDetail, designId: string): ArtifactMeta[] {
  const svg = project.artifact_manifest.filter((item) => item.content_type.split(";")[0].trim() === "image/svg+xml" && item.name.toLowerCase().endsWith(".svg"));
  const exact = svg.filter((item) => item.name.split(/[/.]/).includes(designId));
  return exact.length > 0 ? exact : project.designs.length === 1 && project.designs[0].id === designId ? svg : [];
}

export function snapshotHtml(svg: string): string {
  if (svg.length > 8 * 1024 * 1024 || !/<svg(?:\s|>)/i.test(svg)) throw new Error("This snapshot is not a supported SVG drawing.");
  // Keep untrusted SVG in an image document: its scripts, links and embedded
  // browsing contexts cannot run. Never insert artifact markup into this DOM.
  const source = JSON.stringify(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`).replace(/</g, "\\u003c");
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; frame-src 'none'"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#14161c}#surface{position:absolute;inset:0;overflow:hidden;touch-action:none}img{position:absolute;transform-origin:0 0;max-width:none;pointer-events:none;user-select:none}</style></head><body><div id="surface" role="img" aria-label="Schematic snapshot"><img id="drawing" alt="Published schematic" draggable="false"></div><script>${SNAPSHOT_RUNTIME}\ndrawing.src=${source};</script></body></html>`;
}

// Screen-space pointer coordinates avoid feedback between the camera transform
// and gesture measurements. Each pinch is relative to a fixed gesture baseline.
export const SNAPSHOT_RUNTIME = `
'use strict';
const surface=document.getElementById('surface'),drawing=document.getElementById('drawing');
let scale=1,fitScale=1,x=0,y=0,ready=false,gesture=null,replacement=null;
const pointers=new Map();
function notify(type){window.ReactNativeWebView?.postMessage(JSON.stringify({type,percent:Math.round(scale/fitScale*100)}))}
function size(){return {w:surface.clientWidth,h:surface.clientHeight}}
function bound(){const v=size(),w=drawing.naturalWidth*scale,h=drawing.naturalHeight*scale;x=w<v.w?(v.w-w)/2:Math.max(v.w-w-32,Math.min(32,x));y=h<v.h?(v.h-h)/2:Math.max(v.h-h-32,Math.min(32,y))}
function paint(){bound();drawing.style.transform='translate('+x+'px,'+y+'px) scale('+scale+')';notify('zoom')}
function fit(){if(!ready)return;const v=size();if(v.w<=0||v.h<=0)return;fitScale=Math.min(Math.max(1,v.w-24)/drawing.naturalWidth,Math.max(1,v.h-24)/drawing.naturalHeight);scale=fitScale;x=0;y=0;paint();baseline()}
function zoom(f,cx,cy){if(!ready)return;const next=Math.max(fitScale,Math.min(fitScale*16,scale*f));x=cx-(cx-x)*next/scale;y=cy-(cy-y)*next/scale;scale=next;paint()}
function baseline(){const p=[...pointers.values()];gesture=p.length===1?{p:p[0],x,y}:p.length>=2?{distance:Math.hypot(p[1].x-p[0].x,p[1].y-p[0].y),cx:(p[0].x+p[1].x)/2,cy:(p[0].y+p[1].y)/2,x,y,scale}:null}
function point(e){const r=surface.getBoundingClientRect();return {x:e.clientX-r.left,y:e.clientY-r.top}}
surface.addEventListener('pointerdown',e=>{surface.setPointerCapture(e.pointerId);pointers.set(e.pointerId,point(e));baseline()});
surface.addEventListener('pointermove',e=>{if(!pointers.has(e.pointerId)||!gesture)return;pointers.set(e.pointerId,point(e));const p=[...pointers.values()];if(p.length===1&&gesture.p){x=gesture.x+p[0].x-gesture.p.x;y=gesture.y+p[0].y-gesture.p.y;paint()}else if(p.length>=2&&gesture.distance>0){const d=Math.hypot(p[1].x-p[0].x,p[1].y-p[0].y);scale=Math.max(fitScale,Math.min(fitScale*16,gesture.scale*d/gesture.distance));x=(p[0].x+p[1].x)/2-(gesture.cx-gesture.x)*scale/gesture.scale;y=(p[0].y+p[1].y)/2-(gesture.cy-gesture.y)*scale/gesture.scale;paint()}});
function release(e){pointers.delete(e.pointerId);baseline()}
surface.addEventListener('pointerup',release);surface.addEventListener('pointercancel',release);surface.addEventListener('lostpointercapture',release);
surface.addEventListener('wheel',e=>{e.preventDefault();const p=point(e);zoom(e.deltaY>0?0.8:1.25,p.x,p.y)},{passive:false});
surface.addEventListener('dblclick',e=>{const p=point(e);if(scale>fitScale*1.1)fit();else zoom(2,p.x,p.y)});
function command(e){try{const m=JSON.parse(e.data),v=size();if(m.action==='fit')fit();else if(m.action==='in'||m.action==='out')zoom(m.action==='in'?1.5:1/1.5,v.w/2,v.h/2);else if(m.action==='replace'&&typeof m.source==='string'&&m.source.startsWith('data:image/svg+xml;charset=utf-8,')&&m.source.length<=32*1024*1024){if(ready)replacement={ratio:scale/fitScale,cx:(v.w/2-x)/(drawing.naturalWidth*scale),cy:(v.h/2-y)/(drawing.naturalHeight*scale)};ready=false;drawing.src=m.source}}catch{}}
window.addEventListener('message',command);document.addEventListener('message',command);
new ResizeObserver(()=>fit()).observe(surface);
drawing.onload=()=>{ready=drawing.naturalWidth>0&&drawing.naturalHeight>0;if(ready){if(replacement){const v=size();fitScale=Math.min(Math.max(1,v.w-24)/drawing.naturalWidth,Math.max(1,v.h-24)/drawing.naturalHeight);scale=fitScale*Math.max(1,Math.min(16,replacement.ratio));x=v.w/2-replacement.cx*drawing.naturalWidth*scale;y=v.h/2-replacement.cy*drawing.naturalHeight*scale;replacement=null;paint();baseline()}else fit();notify('ready')}else notify('error')};
drawing.onerror=()=>notify('error');
`;
