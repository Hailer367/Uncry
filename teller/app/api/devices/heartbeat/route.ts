import { NextRequest, NextResponse } from "next/server";
import { upsertDevice, getRelayerUrl } from "@/lib/store";

export async function POST(req: NextRequest){
  try{
    const body = await req.json();
    const relayer = getRelayerUrl();
    if (relayer) {
      try {
        // Device ownership proof flows straight through — Teller never
        // interprets it, so a command minted for device A can only ever be
        // read or consumed by the holder of A's token.
        const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/heartbeat`, { method:"POST", headers:{ "Content-Type":"application/json", ...(req.headers.get("x-device-token") ? { "x-device-token": req.headers.get("x-device-token") as string } : {}) }, body: JSON.stringify(body) });
        if (r.ok) return NextResponse.json(await r.json());
        // Token mismatch on a bound device: surface it, don't fall through
        // to the local store (which would fork the device's state).
        if (r.status === 401) return NextResponse.json(await r.json(), { status: 401 });
      } catch(e){ console.warn("relayer heartbeat forward failed", e); }
    }
    const { deviceId, installed, missing, monitorRunning, batteryOptimized, inUse, screenOn, lastUnlock, ringerMode, model, androidVersion, appVersion, alias, appLabel, hidden } = body || {};
    if(!deviceId) return NextResponse.json({error:"deviceId required"},{status:400});
    const dev = await upsertDevice({ deviceId, model: model||"unknown", androidVersion: androidVersion||"?", appVersion: appVersion||"0.2.1-poss", installed: installed??[], missing: missing??[], monitorRunning: monitorRunning??false, batteryOptimized: batteryOptimized??false, ...(typeof inUse === "boolean" ? { inUse } : {}), ...(typeof screenOn === "boolean" ? { screenOn } : {}), ...(typeof lastUnlock === "string" ? { lastUnlock } : {}), ...(typeof ringerMode === "string" && ["normal", "vibrate", "silent"].includes(ringerMode) ? { ringerMode } : {}), ...(alias ? { alias, appLabel: appLabel || alias } : {}), ...(typeof hidden === "boolean" ? { hidden } : {}) });
    return NextResponse.json({ ok:true, device: dev });
  }catch(e:any){ return NextResponse.json({error:e.message||"bad json"},{status:400}) }
}
