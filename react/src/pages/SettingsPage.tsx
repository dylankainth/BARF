import { useState, useEffect } from "react";

const defaultHost = window.location.hostname || "localhost";

export default function SettingsPage() {
  const [httpHost, setHttpHost] = useState(defaultHost);
  const [robotIp, setRobotIp] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    // try load robot ip from server
    (async () => {
      try {
        const res = await fetch(`http://${httpHost}:8080/api/robot/ip`);
        const json = await res.json();
        if (json && json.success && json.robotIp) setRobotIp(json.robotIp);
      } catch (e) {
        // ignore
      }
    })();
  }, [httpHost]);

  async function saveRobotIp() {
    try {
      const res = await fetch(`http://${httpHost}:8080/api/robot/ip`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ip: robotIp }),
      });
      const json = await res.json();
      if (json && json.success) {
        setMessage("Saved");
        setTimeout(() => setMessage(null), 2000);
      } else {
        setMessage("Failed");
      }
    } catch (e) {
      setMessage("Error");
    }
  }

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-semibold">Settings</h2>

      <section className="space-y-4 columns-1 md:columns-2 xl:columns-3">
        <article
          className="mt-6 mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]"
          style={{ columnSpan: 'all' }}
        >
          <div className="mb-3 text-sm font-semibold tracking-wide">Server</div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-sm mb-1">HTTP Host</label>
              <input
                value={httpHost}
                onChange={(e) => setHttpHost(e.target.value)}
                className="w-full rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0f1115] px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-[rgba(59,130,246,0.12)]"
                placeholder="e.g. my-device.local"
              />
              <p className="text-xs text-muted mt-1">The hostname where the Android device is serving the HTTP API (default detected).</p>
            </div>
            <div>
              <label className="block text-sm mb-1">Robot IP</label>
              <input
                value={robotIp}
                onChange={(e) => setRobotIp(e.target.value)}
                className="w-full rounded-md border border-[rgba(255,255,255,0.06)] bg-[#0f1115] px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-[rgba(59,130,246,0.12)]"
                placeholder="192.168.1.42"
              />
              <div className="flex gap-2 mt-3">
                <button onClick={saveRobotIp} className="btn">Save Robot IP</button>
                <div className="text-sm text-muted self-center">{message}</div>
              </div>
            </div>
          </div>
        </article>
      </section>
    </div>
  );
}
