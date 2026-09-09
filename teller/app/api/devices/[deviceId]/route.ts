import { NextRequest, NextResponse } from "next/server";
import { getRelayerUrl } from "@/lib/store";

export async function GET(req: NextRequest, { params }: { params: { deviceId: string } }){
  const id = params.deviceId;
  const relayer = getRelayerUrl();
  const strip = (d: any) => { if (!d || typeof d !== "object") return d; const { token, deviceToken, ...safe } = d; return safe; };
  if (relayer) {
    try {
      const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/devices/${encodeURIComponent(id)}`, { cache:"no-store" });
      if (r.ok) {
        const j = await r.json();
        return NextResponse.json({ ...j, ...(j.device ? { device: strip(j.device) } : {}) });
      }
      if (r.status===404) return NextResponse.json({error:"device not found"},{status:404});
    } catch(e){ console.warn("relayer single fetch failed", e); }
  }
  // fallback: fetch list and filter (KV/memory)
  try {
    const r = await fetch(`${req.nextUrl.origin}/api/devices`, { cache:"no-store" });
    const j = await r.json();
    const dev = (j.devices||[]).find((d:any)=> d.deviceId===id);
    if (!dev) return NextResponse.json({error:"device not found"},{status:404});
    return NextResponse.json({ device: dev });
  } catch(e:any){ return NextResponse.json({error:e.message},{status:500}) }
}
