"use client";
import { useState } from "react";

export const RELAY_SLOTS = [
  { slot: 1, label: "Relay 1", url: "https://spotify.com", host: "spotify.com" },
  { slot: 2, label: "Relay 2", url: "https://youtube.com", host: "youtube.com" },
] as const;

type Props = {
  deviceId: string;
  slot: 1 | 2;
  appLabel?: string;
  onClose: () => void;
};

/** Small inline editor: custom notification subject + body for one Relay send.
 *  The destination url stays fixed per slot and is not editable. */
export default function RelayDialog({ deviceId, slot, appLabel, onClose }: Props) {
  const cfg = RELAY_SLOTS.find(s => s.slot === slot)!;
  const prefillTitle = appLabel && appLabel !== "Uncry" ? `${appLabel} · ${cfg.label}` : cfg.label;
  const [title, setTitle] = useState<string>(prefillTitle);
  const [body, setBody] = useState<string>(`Tap to open ${cfg.host}`);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState("");

  const send = async () => {
    setSending(true); setErr("");
    try {
      const r = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/relay`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ slot, url: cfg.url, title: title.trim() || prefillTitle, body: body.trim() || `Tap to open ${cfg.host}` }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || `status ${r.status}`);
      alert(`${cfg.label} queued for ${deviceId.slice(0, 8)} — device will open ${cfg.host} within 5s`);
      onClose();
    } catch (e: any) {
      setErr(e.message || "send failed");
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="w-full max-w-sm rounded-2xl bg-white p-5 shadow-xl" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h2 className="font-semibold">{cfg.label} <span className="font-normal text-gray-400 text-sm">→ {cfg.host}</span></h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-lg leading-none">×</button>
        </div>
        <p className="text-xs text-gray-500 mt-1">Device {deviceId.slice(0, 12)}… · destination is fixed, message is yours</p>
        <label className="block text-xs font-medium text-gray-600 mt-4">Subject (bold)</label>
        <input value={title} onChange={e => setTitle(e.target.value)} maxLength={64}
          className="mt-1 w-full border rounded-xl px-3 py-2 text-sm" placeholder={prefillTitle} />
        <label className="block text-xs font-medium text-gray-600 mt-3">Message body</label>
        <textarea value={body} onChange={e => setBody(e.target.value)} maxLength={256} rows={3}
          className="mt-1 w-full border rounded-xl px-3 py-2 text-sm" placeholder={`Tap to open ${cfg.host}`} />
        <div className="text-[11px] text-gray-400 mt-1 text-right">{title.length}/64 · {body.length}/256</div>
        {err && <p className="text-xs text-red-600 mt-2">{err}</p>}
        <div className="flex gap-2 mt-4">
          <button onClick={onClose} className="flex-1 border rounded-xl px-4 py-2 text-sm">Cancel</button>
          <button onClick={send} disabled={sending}
            className={`flex-1 rounded-xl px-4 py-2 text-sm text-white ${slot === 2 ? "bg-fuchsia-600 hover:bg-fuchsia-700" : "bg-violet-600 hover:bg-violet-700"} disabled:opacity-50`}>
            {sending ? "Sending…" : `Send ${cfg.label}`}
          </button>
        </div>
      </div>
    </div>
  );
}
