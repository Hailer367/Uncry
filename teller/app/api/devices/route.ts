import { NextResponse } from "next/server";
import { getStore } from "@/lib/store";

export async function GET(){
  const store = getStore();
  const devices = Array.from(store.values()).sort((a,b)=> new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime());
  return NextResponse.json({ devices, count: devices.length });
}
