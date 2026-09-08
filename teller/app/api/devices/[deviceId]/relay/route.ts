import { NextRequest, NextResponse } from "next/server";
import { getRelayerUrl } from "@/lib/store";

export const dynamic = "force-dynamic";

const RELAY_URL_1 = "https://spotify.com";
const RELAY_URL_2 = "https://youtube.com";

function parseSlot(v: unknown): 1 | 2 {
  return Number(v) === 2 ? 2 : 1;
}

export async function POST(req: NextRequest, { params }: { params: { deviceId: string } }){
  const id = params.deviceId;
  const relayer = getRelayerUrl();
  if (!relayer) return NextResponse.json({error:"Relayer not configured"}, {status:503});
  try{
    const body = await req.json().catch(()=>({}));
    const slot = parseSlot(body?.slot);
    const url = (typeof body?.url === "string" && body.url.trim())
      ? body.url.trim().slice(0, 512)
      : (slot === 2 ? RELAY_URL_2 : RELAY_URL_1);
    const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/relay`, {
      method:"POST",
      headers: { "Content-Type":"application/json", ...(process.env.RELAYER_SECRET ? {"x-relayer-secret": process.env.RELAYER_SECRET} : {}) },
      body: JSON.stringify({ deviceId: id, url, slot })
    });
    const j = await r.json();
    if (!r.ok) return NextResponse.json(j, {status:r.status});
    return NextResponse.json(j);
  }catch(e:any){ return NextResponse.json({error:e.message},{status:500}) }
}
