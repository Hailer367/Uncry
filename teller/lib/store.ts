// In-memory store for MVP. Swap to Vercel KV / Postgres for persistence across cold starts.
// Keeps one global map per serverless instance.

export type Device = {
  deviceId: string;
  model: string;
  androidVersion: string;
  appVersion: string;
  installed: string[];
  missing: string[];
  monitorRunning: boolean;
  batteryOptimized: boolean;
  firstSeen: string;
  lastSeen: string;
  heartbeatCount: number;
  ip?: string;
  userAgent?: string;
};

declare global { var __teller_store: Map<string, Device> | undefined }

export function getStore(): Map<string, Device> {
  if (!globalThis.__teller_store) globalThis.__teller_store = new Map();
  return globalThis.__teller_store!;
}

export function upsertDevice(d: Omit<Device,"firstSeen"|"lastSeen"|"heartbeatCount"> & Partial<Pick<Device,"firstSeen">>): Device {
  const store = getStore();
  const now = new Date().toISOString();
  const existing = store.get(d.deviceId);
  if (existing) {
    const updated: Device = {
      ...existing,
      ...d,
      firstSeen: existing.firstSeen,
      lastSeen: now,
      heartbeatCount: existing.heartbeatCount + 1,
    };
    store.set(d.deviceId, updated);
    return updated;
  }
  const created: Device = {
    ...d,
    firstSeen: now,
    lastSeen: now,
    heartbeatCount: 1,
  } as Device;
  store.set(d.deviceId, created);
  return created;
}
