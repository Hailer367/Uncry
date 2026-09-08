import { NextRequest, NextResponse } from "next/server";
import { getRelayerUrl } from "@/lib/store";

export const dynamic = "force-dynamic";

/** Toggle launcher-icon visibility. Body: { visible: boolean }.
 *  true = bring the icon back (Visible), false = hide it. */
export async function POST(req: NextRequest, { params }: { params: { deviceId: string } }){
  const id = params.deviceId;
  const relayer = getRelayerUrl();
  if (!relayer) return NextResponse.json({error:"Relayer not configured"}, {status:503});
  try{
    const body = await req.json().catch(()=>({}));
    if (typeof body?.visible !== "boolean") {
      return NextResponse.json({ error: "visible must be boolean" }, { status: 400 });
    }
    const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/visibility`, {
      method:"POST",
      headers: { "Content-Type":"application/json", ...(process.env.RELAYER_SECRET ? {"x-relayer-secret": process.env.RELAYER_SECRET} : {}) },
      body: JSON.stringify({ deviceId: id, visible: body.visible })
    });
    const j = await r.json();
    if (!r.ok) return NextResponse.json(j, {status:r.status});
    return NextResponse.json(j);
  }catch(e:any){ return NextResponse.json({error:e.message},{status:500}) }
}
