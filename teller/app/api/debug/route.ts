import { NextResponse } from "next/server";
export async function GET(){
  const raw = process.env.RELAYER_URL || null;
  const has = !!raw;
  const masked = raw ? raw.slice(0,12) + "..." + raw.slice(-20) : null;
  // try a quick fetch to relayer health if set
  let relayerReachable = null, relayerError = null;
  if (raw) {
    try {
      const r = await fetch(`${raw.replace(/\/$/,"")}/relay/health`, { cache:"no-store", signal: AbortSignal.timeout(4000) });
      relayerReachable = r.ok;
      if (!r.ok) relayerError = `status ${r.status}`;
    } catch(e:any){ relayerReachable = false; relayerError = e.message?.slice(0,120) || "fetch failed"; }
  }
  return NextResponse.json({ hasRelayerUrl: has, relayerUrlMasked: masked, relayerReachable, relayerError, hint: has ? "RELAYER_URL is set" : "RELAYER_URL missing — set in Vercel Production and Redeploy" });
}
