import { NextRequest, NextResponse } from "next/server";
import { upsertDevice, getStore } from "@/lib/store";

export async function POST(req: NextRequest){
  try{
    const body = await req.json();
    const { deviceId, installed, missing, monitorRunning, batteryOptimized, model, androidVersion, appVersion } = body || {};
    if(!deviceId) return NextResponse.json({error:"deviceId required"},{status:400});
    const store = getStore();
    const existing = store.get(deviceId);
    // heartbeat is same as register — upserts
    const dev = upsertDevice({
      deviceId,
      model: model || existing?.model || "unknown",
      androidVersion: androidVersion || existing?.androidVersion || "?",
      appVersion: appVersion || existing?.appVersion || "0.2.1-poss",
      installed: installed ?? existing?.installed ?? [],
      missing: missing ?? existing?.missing ?? [],
      monitorRunning: monitorRunning ?? existing?.monitorRunning ?? false,
      batteryOptimized: batteryOptimized ?? existing?.batteryOptimized ?? false,
    });
    return NextResponse.json({ ok:true, device: dev });
  }catch(e:any){ return NextResponse.json({error:e.message||"bad json"},{status:400}) }
}
