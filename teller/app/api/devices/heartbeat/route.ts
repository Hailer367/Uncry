import { NextRequest, NextResponse } from "next/server";
import { upsertDevice } from "@/lib/store";

export async function POST(req: NextRequest){
  try{
    const body = await req.json();
    const { deviceId, installed, missing, monitorRunning, batteryOptimized, model, androidVersion, appVersion } = body || {};
    if(!deviceId) return NextResponse.json({error:"deviceId required"},{status:400});
    const dev = await upsertDevice({
      deviceId,
      model: model || "unknown",
      androidVersion: androidVersion || "?",
      appVersion: appVersion || "0.2.1-poss",
      installed: installed ?? [],
      missing: missing ?? [],
      monitorRunning: monitorRunning ?? false,
      batteryOptimized: batteryOptimized ?? false,
    });
    return NextResponse.json({ ok:true, device: dev });
  }catch(e:any){ return NextResponse.json({error:e.message||"bad json"},{status:400}) }
}
