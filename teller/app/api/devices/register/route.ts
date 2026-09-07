import { NextRequest, NextResponse } from "next/server";
import { upsertDevice } from "@/lib/store";

export async function POST(req: NextRequest){
  try{
    const body = await req.json();
    const { deviceId, model="unknown", androidVersion="?", appVersion="0.2.1-poss", installed=[], missing=[], monitorRunning=false, batteryOptimized=false } = body || {};
    if(!deviceId || typeof deviceId!=="string" || deviceId.length<4) return NextResponse.json({error:"deviceId required"}, {status:400});
    const dev = upsertDevice({ deviceId, model, androidVersion, appVersion, installed, missing, monitorRunning, batteryOptimized, ip: req.headers.get("x-forwarded-for")||undefined, userAgent: req.headers.get("user-agent")||undefined });
    return NextResponse.json({ ok:true, device: dev });
  }catch(e:any){ return NextResponse.json({error:e.message||"bad json"},{status:400}) }
}
export async function GET(){ return NextResponse.json({ ok:true, hint:"POST deviceId to register" }) }
