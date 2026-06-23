import { useEffect, useState, useRef } from "react";

interface SerialLine {
  dir: "rx" | "tx" | "status";
  text: string;
  ts: number;
}

interface SerialMonitorProps {
  phoneIp: string;
  connected: boolean;
}

export default function SerialMonitor({ phoneIp, connected: phoneConnected }: SerialMonitorProps) {
  const [esp32Connected, setEsp32Connected] = useState(false);
  const [lines, setLines] = useState<SerialLine[]>([]);
  const [input, setInput] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!phoneConnected || !phoneIp) return;

    const ws = new WebSocket(`ws://${phoneIp}:8081/api/serial`);
    wsRef.current = ws;

    ws.onopen = () => {
      addLine("status", "Connected to phone serial relay");
    };

    ws.onclose = () => {
      setEsp32Connected(false);
      addLine("status", "Disconnected from phone");
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);

        // Only handle serial-related messages — ignore detections, etc.
        if (msg.type === "serial_status") {
          setEsp32Connected(msg.connected ?? false);
          addLine("status", msg.connected ? "ESP32 connected" : "ESP32 disconnected");
        } else if (msg.type === "serial_rx" && msg.data) {
          addLine("rx", msg.data);
        }
        // serial_tx is added locally when we send — nothing to do here
      } catch {}
    };

    return () => ws.close();
  }, [phoneIp, phoneConnected]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [lines]);

  function addLine(dir: SerialLine["dir"], text: string) {
    setLines((prev) => [...prev.slice(-500), { dir, text, ts: Date.now() }]);
  }

  function send() {
    const data = input.trim();
    if (!data || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    wsRef.current.send(JSON.stringify({ action: "write", data: data + "\n" }));
    addLine("tx", data);
    setInput("");
  }

  function fmtTime(ts: number) {
    return new Date(ts).toLocaleTimeString([], { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" });
  }

  return (
    <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">

      {/* Header */}
      <div className="flex items-center justify-between mb-3">
        <div className="text-sm font-semibold tracking-wide">Serial Monitor</div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5">
            <span className={`w-2 h-2 rounded-full ${phoneConnected ? "bg-blue-400" : "bg-zinc-600"}`} />
            <span className={`text-xs ${phoneConnected ? "text-blue-400" : "text-zinc-600"}`}>Phone</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className={`w-2 h-2 rounded-full ${esp32Connected ? "bg-green-400" : "bg-zinc-600"}`} />
            <span className={`text-xs ${esp32Connected ? "text-green-400" : "text-zinc-600"}`}>ESP32</span>
          </div>
          <button
            onClick={() => setLines([])}
            className="text-xs text-zinc-500 hover:text-zinc-300 transition"
          >
            Clear
          </button>
        </div>
      </div>

      {/* Legend */}
      <div className="flex gap-4 mb-2 text-xs text-zinc-500">
        <span><span className="text-green-400 font-mono">RX</span> — from ESP32</span>
        <span><span className="text-blue-400 font-mono">TX</span> — sent to ESP32</span>
        <span><span className="text-zinc-500 font-mono">--</span> — status</span>
      </div>

      {/* Log */}
      <div className="bg-black rounded border border-zinc-800 p-2 h-48 overflow-y-auto font-mono text-xs mb-3">
        {lines.length === 0 ? (
          <div className="text-zinc-600">
            {phoneConnected
              ? esp32Connected
                ? "ESP32 connected — waiting for data…"
                : "Waiting for ESP32 to connect via USB…"
              : "Connect to phone first"}
          </div>
        ) : (
          lines.map((line, i) => (
            <div key={i} className="flex gap-2 leading-5">
              <span className="text-zinc-600 flex-shrink-0">{fmtTime(line.ts)}</span>
              {line.dir === "rx" && (
                <>
                  <span className="text-green-400 font-bold flex-shrink-0">RX</span>
                  <span className="text-green-300 break-all">{line.text}</span>
                </>
              )}
              {line.dir === "tx" && (
                <>
                  <span className="text-blue-400 font-bold flex-shrink-0">TX</span>
                  <span className="text-blue-300 break-all">{line.text}</span>
                </>
              )}
              {line.dir === "status" && (
                <>
                  <span className="text-zinc-500 flex-shrink-0">--</span>
                  <span className="text-zinc-400 italic">{line.text}</span>
                </>
              )}
            </div>
          ))
        )}
        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div className="flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && send()}
          placeholder={esp32Connected ? '{"m":[100,100,100,100]}' : "ESP32 not connected"}
          disabled={!esp32Connected}
          className="flex-1 bg-zinc-900 border border-zinc-700 rounded px-2 py-1 text-sm font-mono disabled:opacity-40"
        />
        <button
          onClick={send}
          disabled={!esp32Connected || !input.trim()}
          className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 rounded text-sm font-semibold disabled:opacity-40 transition"
        >
          Send
        </button>
      </div>
    </article>
  );
}
