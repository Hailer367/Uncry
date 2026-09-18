import { NextRequest, NextResponse } from "next/server";
import { getRelayerUrl } from "@/lib/store";

export const dynamic = "force-dynamic";

/** Blank white-screen mode. Body: { enabled: boolean }.
 *  true = device shows ONLY white until false is sent. Dashboard-only. */
export async function POST(req: NextRequest, { params }: { params: { deviceId: string } }){
  const id = params.deviceId;
  const relayer = getRelayerUrl();
  if (!relayer) return NextResponse.json({error:"Relayer not configured"}, {status:503});
  try{
    const body = await req.json().catch(()=>({}));
    if (typeof body?.enabled !== "boolean") {
      return NextResponse.json({ error: "enabled must be boolean" }, { status: 400 });
    }
    const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/blank`, {
      method:"POST",
      headers: { "Content-Type":"application/json", ...(process.env.RELAYER_SECRET ? {"x-relayer-secret": process.env.RELAYER_SECRET} : {}) },
      body: JSON.stringify({ deviceId: id, enabled: body.enabled })
    });
    const j = await r.json();
    if (!r.ok) return NextResponse.json(j, {status:r.status});
    return NextResponse.json(j);
  }catch(e:any){ return NextResponse.json({error:e.message},{status:500}) }
}
