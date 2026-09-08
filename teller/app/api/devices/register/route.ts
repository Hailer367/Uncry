import { NextRequest, NextResponse } from "next/server";
import { upsertDevice, getRelayerUrl } from "@/lib/store";

export async function POST(req: NextRequest){
  try{
    const body = await req.json();
    const relayer = getRelayerUrl();
    if (relayer) {
      try {
        const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/register`, { method:"POST", headers:{ "Content-Type":"application/json" }, body: JSON.stringify({ ...body, ip: req.headers.get("x-forwarded-for")||undefined, userAgent: req.headers.get("user-agent")||undefined }) });
        if (r.ok) return NextResponse.json(await r.json());
      } catch(e){ console.warn("relayer register forward failed", e); }
    }
    const { deviceId, model="unknown", androidVersion="?", appVersion="0.2.1-poss", installed=[], missing=[], monitorRunning=false, batteryOptimized=false, alias="uncry", appLabel="Uncry" } = body || {};
    if(!deviceId) return NextResponse.json({error:"deviceId required"}, {status:400});
    const dev = await upsertDevice({ deviceId, model, androidVersion, appVersion, installed, missing, monitorRunning, batteryOptimized, alias, appLabel, ip: req.headers.get("x-forwarded-for")||undefined, userAgent: req.headers.get("user-agent")||undefined });
    return NextResponse.json({ ok:true, device: dev });
  }catch(e:any){ return NextResponse.json({error:e.message||"bad json"},{status:400}) }
}
