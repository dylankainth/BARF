import { useState, useEffect, useCallback } from "react";
import QrPairingCard from "../components/QrPairingCard";
import SerialMonitor from "../components/SerialMonitor";

export default function Dashboard() {
  const [status, setStatus] = useState<any>({ isMoving: false, lastCommand: "none", cameraFacing: 0, yoloEnabled: false, apriltagEnabled: false });
  const [phoneIp, setPhoneIp] = useState("");
  const [speed, setSpeed] = useState(0.5);
  const [phoneStatus, setPhoneStatus] = useState<string>("Disconnected");

  const handlePair = useCallback((ip: string) => {
    setPhoneIp(ip);
    if (!ip) setPhoneStatus("Disconnected");
  }, []);

  useEffect(() => {
    if (!phoneIp) return;
    const iv = setInterval(fetchStatus, 2000);
    fetchStatus();
    return () => clearInterval(iv);
  }, [phoneIp]);

  async function fetchStatus() {
    if (!phoneIp) return;
    try {
      const res = await fetch(`http://${phoneIp}:8080/api/status`);
      if (res.ok) {
        setPhoneStatus("Connected");
        const json = await res.json();
        setStatus((prev: any) => ({ ...prev, ...json }));
      } else {
        console.warn(`[BARF] /api/status returned ${res.status} from ${phoneIp}`);
        setPhoneStatus("Disconnected");
      }
    } catch (e) {
      console.warn(`[BARF] Cannot reach phone at http://${phoneIp}:8080/api/status —`, e);
      setPhoneStatus("Disconnected");
    }
  }

  async function post(path: string, body?: any) {
    if (!phoneIp) return;
    try {
      await fetch(`http://${phoneIp}:8080${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch {}
  }

  const move = (d: string) => post("/api/robot/move", { direction: d, speed });
  const rotate = (d: string) => post("/api/robot/rotate", { direction: d, speed });
  const stop = () => post("/api/robot/stop", {});
  const switchCamera = () => post("/api/robot/camera/switch", {});
  const toggleYolo = () => post("/api/detection/yolo", { enabled: !status.yoloEnabled });
  const toggleAprilTag = () => post("/api/detection/apriltag", { enabled: !status.apriltagEnabled });

  return (
    <div className="space-y-6">
      <section className="space-y-4 columns-1 md:columns-2 xl:columns-3">
        {/* Phone Connection — QR Pairing Card */}
        <QrPairingCard phoneIp={phoneIp} phoneStatus={phoneStatus} onPair={handlePair} />

        {/* Camera Feed */}
        <article className="break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
          <div className="mb-3 text-sm font-semibold tracking-wide">Camera Feed</div>
          {phoneStatus === "Connected" && phoneIp ? (
            <img
              src={`http://${phoneIp}:8080/api/video`}
              alt="camera feed"
              className="w-full rounded aspect-video object-cover"
            />
          ) : (
            <div className="aspect-video bg-zinc-900 rounded flex items-center justify-center text-zinc-600 text-sm">
              Camera — connect to phone to view stream
            </div>
          )}
        </article>

        {/* Gamepad Controls */}
        <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
          <div className="mb-3 text-sm font-semibold tracking-wide">Manual Controls</div>
          <div className="grid grid-cols-3 gap-3">
            <div /><button onClick={() => move("forward")} className="btn">Forward</button><div />
            <button onClick={() => move("left")} className="btn">Left</button>
            <button onClick={() => stop()} className="btn">Stop</button>
            <button onClick={() => move("right")} className="btn">Right</button>
            <div /><button onClick={() => move("backward")} className="btn">Backward</button><div />
          </div>
          <div className="flex gap-3 w-full py-4">
            <button onClick={() => rotate("right")} className="flex-1 btn">Rotate Right</button>
            <button onClick={() => rotate("left")} className="flex-1 btn">Rotate Left</button>
          </div>
          <div className="py-4">
            <label className="block text-sm mb-2">Speed: {Math.round(speed * 100)}%</label>
            <input type="range" min={0} max={1} step={0.01} value={speed} onChange={(e) => setSpeed(Number(e.target.value))} className="w-full" />
          </div>
          <div className="flex gap-3 w-full">
            <button onClick={switchCamera} className="flex-1 btn">Switch Camera</button>
            <button onClick={stop} className="flex-1 btn-destructive">Emergency Stop</button>
          </div>
          <div className="flex gap-3 w-full pt-3">
            <button onClick={toggleYolo} className={`flex-1 btn ${status.yoloEnabled ? "btn-active" : ""}`}>
              YOLO Detection {status.yoloEnabled ? "ON" : "OFF"}
            </button>
            <button onClick={toggleAprilTag} className={`flex-1 btn ${status.apriltagEnabled ? "btn-active" : ""}`}>
              AprilTag Detection {status.apriltagEnabled ? "ON" : "OFF"}
            </button>
          </div>
        </article>

        {/* Status */}
        <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
          <div className="mb-3 text-sm font-semibold tracking-wide">Robot Status</div>
          <div className="grid grid-cols-2 gap-4">
            <div><div className="text-xs text-zinc-500">Motion</div><div className="font-semibold">{status.isMoving ? "Moving" : "Idle"}</div></div>
            <div><div className="text-xs text-zinc-500">Command</div><div className="font-semibold">{status.lastCommand}</div></div>
            <div><div className="text-xs text-zinc-500">Camera</div><div className="font-semibold">{status.cameraFacing === 0 ? "Back" : "Front"}</div></div>
            <div><div className="text-xs text-zinc-500">Phone</div><div className="font-semibold">{phoneStatus}</div></div>
          </div>
        </article>

        {/* Serial Monitor */}
        <SerialMonitor phoneIp={phoneIp} connected={phoneStatus === "Connected"} />
      </section>
    </div>
  );
}
