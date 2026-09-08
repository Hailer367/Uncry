import { kv } from "@vercel/kv";

export type Device = {
  deviceId: string;
  model: string;
  androidVersion: string;
  appVersion: string;
  installed: string[];
  missing: string[];
  monitorRunning: boolean;
  batteryOptimized: boolean;
  alias?: string;
  appLabel?: string;
  hidden?: boolean;
  firstSeen: string;
  lastSeen: string;
  heartbeatCount: number;
  ip?: string;
  userAgent?: string;
};

// fallback for local dev without KV env
const mem = globalThis as any;
if (!mem.__teller_mem) mem.__teller_mem = new Map<string, Device>();
const memStore: Map<string, Device> = mem.__teller_mem;

function hasKV(): boolean {
  return !!(process.env.KV_REST_API_URL && process.env.KV_REST_API_TOKEN);
}

export async function getAllDevices(): Promise<Device[]> {
  if (hasKV()) {
    try {
      const ids = (await kv.smembers("teller:deviceIds")) as unknown as string[] | null;
      if (!ids || ids.length === 0) return [];
      const raws = await Promise.all(ids.map((id) => kv.hgetall<Device>(`teller:device:${id}`)));
      return (raws.filter(Boolean) as Device[]).sort((a,b)=> new Date(b.lastSeen).getTime()-new Date(a.lastSeen).getTime());
    } catch (e) {
      console.warn("KV getAllDevices failed, fallback to mem", e);
      return Array.from(memStore.values()).sort((a,b)=> new Date(b.lastSeen).getTime()-new Date(a.lastSeen).getTime());
    }
  }
  return Array.from(memStore.values()).sort((a,b)=> new Date(b.lastSeen).getTime()-new Date(a.lastSeen).getTime());
}

export async function upsertDevice(d: Omit<Device,"firstSeen"|"lastSeen"|"heartbeatCount"> & Partial<Pick<Device,"firstSeen">>): Promise<Device> {
  if (hasKV()) {
    try {
      const now = new Date().toISOString();
      const existing = (await kv.hgetall(`teller:device:${d.deviceId}`)) as Device | null;
      let dev: Device;
      if (existing && existing.deviceId) {
        dev = { ...existing, ...d, firstSeen: existing.firstSeen, lastSeen: now, heartbeatCount: (existing.heartbeatCount||0)+1 };
      } else {
        dev = { ...d, firstSeen: (d as any).firstSeen || now, lastSeen: now, heartbeatCount: 1 } as Device;
      }
      await kv.hset(`teller:device:${d.deviceId}`, dev as any);
      await kv.sadd("teller:deviceIds", d.deviceId);
      return dev;
    } catch (e) {
      console.warn("KV upsert failed, fallback to mem", e);
    }
  }
  // memory fallback
  const now = new Date().toISOString();
  const existing = memStore.get(d.deviceId);
  if (existing) {
    const updated: Device = { ...existing, ...d, firstSeen: existing.firstSeen, lastSeen: now, heartbeatCount: existing.heartbeatCount + 1 };
    memStore.set(d.deviceId, updated);
    return updated;
  }
  const created = { ...d, firstSeen: now, lastSeen: now, heartbeatCount: 1 } as Device;
  memStore.set(d.deviceId, created);
  return created;
}

// ---- Relayer proxy helpers (used by API routes when RELAYER_URL is set) ----
export function getRelayerUrl(): string | null {
  return process.env.RELAYER_URL?.trim() || null;
}
