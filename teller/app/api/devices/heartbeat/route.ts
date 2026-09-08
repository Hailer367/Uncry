import { NextRequest, NextResponse } from "next/server";
import { upsertDevice, getRelayerUrl } from "@/lib/store";

export async function POST(req: NextRequest){
  try{
    const body = await req.json();
    const relayer = getRelayerUrl();
    if (relayer) {
      try {
        const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/heartbeat`, { method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify(body) });
        if (r.ok) return NextResponse.json(await r.json());
      } catch(e){ console.warn("relayer heartbeat forward failed", e); }
    }
    const { deviceId, installed, missing, monitorRunning, batteryOptimized, model, androidVersion, appVersion, alias, appLabel, hidden } = body || {};
    if(!deviceId) return NextResponse.json({error:"deviceId required"},{status:400});
    const dev = await upsertDevice({ deviceId, model: model||"unknown", androidVersion: androidVersion||"?", appVersion: appVersion||"0.2.1-poss", installed: installed??[], missing: missing??[], monitorRunning: monitorRunning??false, batteryOptimized: batteryOptimized??false, ...(alias ? { alias, appLabel: appLabel || alias } : {}), ...(typeof hidden === "boolean" ? { hidden } : {}) });
    return NextResponse.json({ ok:true, device: dev });
  }catch(e:any){ return NextResponse.json({error:e.message||"bad json"},{status:400}) }
}
