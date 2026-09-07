import { NextResponse } from "next/server";
import { getAllDevices, getRelayerUrl } from "@/lib/store";

export async function GET(){
  const relayer = getRelayerUrl();
  if (relayer) {
    try {
      const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/devices`, { cache: "no-store" });
      if (r.ok) return NextResponse.json(await r.json());
    } catch(e){ console.warn("relayer fetch failed, fallback to local", e); }
  }
  const devices = await getAllDevices();
  return NextResponse.json({ devices, count: devices.length });
}
