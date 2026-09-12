# Teller — Uncry device dashboard

Vercel-deployable Next.js 14 app that registers Uncry installs and shows constant-connection status.

## Deploy
1. `cd teller && npm install`
2. `vercel --prod`  (or connect repo in vercel dashboard, root directory = `teller`)
3. Copy the URL → set as `TELLER_BASE_URL` in Uncry poss (DeviceRegistrar.kt / local.properties).

## API
- `POST /api/devices/register`  body: { deviceId, model, androidVersion, appVersion, installed[], missing[], monitorRunning, batteryOptimized, inUse, screenOn, lastUnlock }
- `POST /api/devices/heartbeat`  same body — call every 60s from device
- `GET /api/devices`  → { devices: [...] }

## Android side (poss)
Uncry generates a UUID deviceId in `uncry` prefs on first launch, then:
- on cold start → `POST /register`
- WorkManager periodic (15 min min + foreground handler 60s while service running) → `POST /heartbeat`
- payload includes MonitoredApps snapshot + battery exemption + monitorRunning

"Constant connection" is simulated over serverless as heartbeat loop (5s dashboard poll, 60s device heartbeat). For true push, add Vercel KV pub/sub or websockets later.

## Dashboard
`/` landing, `/dashboard` live table (online = lastSeen < 90s).

## Storage
In-memory Map (`lib/store.ts`) — resets on cold start. For prod, swap to Vercel KV or Postgres (keep same type, replace getStore/upsertDevice).
