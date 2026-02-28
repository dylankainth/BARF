import { useState, useEffect } from "react";

const getHttpBase = () => {
    const host = window.location.hostname || "localhost";
    return `http://${host}:8080`;
};

export default function ControlPage() {
    const [status, setStatus] = useState<any>({ isMoving: false, lastCommand: "none", cameraFacing: 0 });
    const [speed, setSpeed] = useState(0.5);
    const base = getHttpBase();

    useEffect(() => {
        const iv = setInterval(fetchStatus, 1000);
        fetchStatus();
        return () => clearInterval(iv);
    }, []);

    async function fetchStatus() {
        try {
            const res = await fetch(`${base}/api/robot/status`);
            const json = await res.json();
            if (json.success) setStatus({ isMoving: json.isMoving, lastCommand: json.lastCommand, cameraFacing: json.cameraFacing });
        } catch (e) {
            // ignore
        }
    }

    async function post(path: string, body: any) {
        await fetch(`${base}${path}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body || {}),
        });
    }

    const move = (direction: string) => post("/api/robot/move", { direction, speed });
    const rotate = (direction: string) => post("/api/robot/rotate", { direction, speed });
    const stop = () => post("/api/robot/stop", {});
    const switchCamera = () => post("/api/robot/camera/switch", {});

    return (

        <div className="space-y-6">


            <section className="space-y-4 columns-1 md:columns-2 xl:columns-3">
                <article
                    className="mt-6 mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]"
                    style={{ columnSpan: 'all' }}
                >
                    <div className="mb-3 text-sm font-semibold tracking-wide">Camera Feed</div>
                    <img
                        alt="Camera"
                        src={`${getHttpBase()}/stream/video`}
                        className="w-full block object-cover"
                    />


                </article>

                <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
                    <div className="mb-3 text-sm font-semibold tracking-wide">Gamepad</div>

                    <div className="grid grid-cols-3 gap-3">
                        <div />
                        <button onClick={() => move("forward")} className="btn">Forward</button>
                        <div />

                        <button onClick={() => move("left")} className="btn">Left</button>
                        <button onClick={() => stop()} className="btn center">Stop</button>
                        <button onClick={() => move("right")} className="btn">Right</button>

                        <div />
                        <button onClick={() => move("backward")} className="btn">Backward</button>
                        <div />
                    </div>

                    <div className="flex gap-3 w-full py-4">
                        <button onClick={() => rotate("right")} className="flex-1 btn">Rotate Right</button>
                        <button onClick={() => rotate("left")} className="flex-1 btn">Rotate Left</button>
                    </div>

                    <div className="flex gap-3 w-full py-4">
                        <label className="block text-sm mb-2">Speed: {Math.round(speed * 100)}%</label>
                        <input type="range" min={0} max={1} step={0.01} value={speed} onChange={(e) => setSpeed(Number(e.target.value))} className="w-full" />
                    </div>

                    <div className="flex gap-3 w-full py-4">
                        <button onClick={() => switchCamera()} className="flex-1 btn">Switch Camera</button>
                        <button onClick={() => stop()} className="flex-1 btn-destructive">Emergency Stop</button>
                    </div>

                </article>



                <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
                    <div className="mb-3 text-sm font-semibold tracking-wide">Status</div>
                    <div className="grid grid-cols-3 gap-4">
                        <div>
                            <div className="text-xs text-muted">Motion</div>
                            <div className="font-semibold">{status.isMoving ? "Moving" : "Idle"}</div>
                        </div>
                        <div>
                            <div className="text-xs text-muted">Last Command</div>
                            <div className="font-semibold">{status.lastCommand}</div>
                        </div>
                        <div>
                            <div className="text-xs text-muted">Camera</div>
                            <div className="font-semibold">{status.cameraFacing === 0 ? "Back" : "Front"}</div>
                        </div>
                    </div>

                </article>




            </section>
        </div>
    );
}
