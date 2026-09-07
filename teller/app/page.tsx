import Link from "next/link";
export default function Home(){
  return <main className="max-w-3xl mx-auto px-6 py-16">
    <p className="text-xs tracking-widest text-muted uppercase">Uncry · Teller</p>
    <h1 className="text-4xl font-bold mt-2">Teller</h1>
    <p className="text-muted mt-3">Registration dashboard for Uncry installs. Devices call <code className="bg-gray-100 px-1 rounded">/api/devices/*</code> to register + heartbeat — you track them here.</p>
    <div className="flex gap-3 mt-8">
      <Link href="/dashboard" className="bg-ink text-white px-5 py-2.5 rounded-xl">Open dashboard</Link>
      <a href="https://github.com/Hailer367/Uncry" className="border px-5 py-2.5 rounded-xl">Uncry repo</a>
    </div>
    <div className="mt-12 border rounded-2xl p-6">
      <h2 className="font-semibold">How it works (poss branch)</h2>
      <ol className="list-decimal ml-5 mt-3 text-sm space-y-1 text-muted">
        <li>Uncry generates a deviceId on first launch (stored in prefs)</li>
        <li>POST <code>/api/devices/register</code> on every cold start / install</li>
        <li>POST <code>/api/devices/heartbeat</code> every 60s (via WorkManager) — constant connection simulation over serverless</li>
        <li>Dashboard polls <code>/api/devices</code> and shows lastSeen · install state · battery</li>
      </ol>
      <p className="text-xs text-muted mt-4">Next step: per-user login so users track their own installs. For now all devices are global (beta / internal).</p>
    </div>
    <p className="text-xs text-muted mt-8">Deploy: <code>vercel --prod</code> — set <code>TELLER_BASE_URL</code> in Uncry to this URL.</p>
  </main>
}
