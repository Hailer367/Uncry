import { NextRequest, NextResponse } from "next/server";
import { getRelayerUrl } from "@/lib/store";

export async function GET(req: NextRequest, { params }: { params: { deviceId: string } }){
  const id = params.deviceId;
  const relayer = getRelayerUrl();
  if (!relayer) return NextResponse.json({command:null});
  try{
    const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/poll/${encodeURIComponent(id)}`, { cache:"no-store" });
    const j = await r.json();
    return NextResponse.json(j, {status:r.status});
  }catch(e:any){ return NextResponse.json({command:null}) }
}
