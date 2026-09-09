import { NextResponse } from "next/server";
import { getAllDevices, getRelayerUrl } from "@/lib/store";

export const dynamic = "force-dynamic";

export async function GET(){
  const relayer = getRelayerUrl();
  // Device tokens must never reach the browser: without them, a dashboard
  // viewer (or XSS) can't poll/heartbeat as another device.
  const strip = (d: any) => { if (!d || typeof d !== "object") return d; const { token, deviceToken, ...safe } = d; return safe; };
  if (relayer) {
    try {
      const r = await fetch(`${relayer.replace(/\/$/,"")}/relay/devices`, { cache: "no-store" });
      if (r.ok) {
        const j = await r.json();
        return NextResponse.json({ ...j, devices: (j.devices || []).map(strip) });
      }
    } catch(e){ console.warn("relayer fetch failed, fallback to local", e); }
  }
  const devices = await getAllDevices();
  return NextResponse.json({ devices: devices.map(strip), count: devices.length });
}
