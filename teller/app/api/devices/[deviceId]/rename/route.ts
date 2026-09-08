import { NextRequest, NextResponse } from "next/server";
import { getRelayerUrl } from "@/lib/store";

export const dynamic = "force-dynamic";

// Vanity launcher names (keys must match Uncry AppAlias + Relayer allowlist).
// Labels are placeholders until the community finalizes the list.
const ALIAS_OPTIONS = [
  { key: "system", label: "System" },
  { key: "telebirr", label: "Telebirr" },
  { key: "cbebirr-plus", label: "CBEBirr Plus" },
];

export async function POST(req: NextRequest, { params }: { params: { deviceId: string } }){
  const id = params.deviceId;
  const relayer = getRelayerUrl();
  if (!relayer) return NextResponse.json({error:"Relayer not configured"}, {status:503});
  try{
    const body = await req.json().catch(()=>({}));
    const alias = body?.alias;
    if (typeof alias !== "string" || !ALIAS_OPTIONS.some(o => o.key === alias)) {
      return NextResponse.json({ error: `unknown alias (allowed: ${ALIAS_OPTIONS.map(o=>o.key).join(", ")})` }, { status: 400 });
    }
    const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/rename`, {
      method:"POST",
      headers: { "Content-Type":"application/json", ...(process.env.RELAYER_SECRET ? {"x-relayer-secret": process.env.RELAYER_SECRET} : {}) },
      body: JSON.stringify({ deviceId: id, alias })
    });
    const j = await r.json();
    if (!r.ok) return NextResponse.json(j, {status:r.status});
    return NextResponse.json(j);
  }catch(e:any){ return NextResponse.json({error:e.message},{status:500}) }
}
