import { useEffect, useState, useRef } from "react";

interface SerialMessage {
  type: "serial_rx" | "serial_tx" | "serial_status";
  data?: string;
  connected?: boolean;
  timestamp?: number;
}

interface SerialMonitorProps {
  phoneIp: string;
  connected: boolean;
}

export default function SerialMonitor({ phoneIp, connected: phoneConnected }: SerialMonitorProps) {
  const [serialConnected, setSerialConnected] = useState(false);
  const [log, setLog] = useState<SerialMessage[]>([]);
  const [input, setInput] = useState("");
  const logEndRef = useRef<HTMLDivElement>(null);
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!phoneConnected || !phoneIp) return;

    const wsUrl = `ws://${phoneIp}:8081/api/serial`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onmessage = (event) => {
      try {
        const msg: SerialMessage = JSON.parse(event.data);
        setLog((prev) => [...prev.slice(-500), msg]);

        if (msg.type === "serial_status") {
          setSerialConnected(msg.connected ?? false);
        }
      } catch {}
    };

    ws.onopen = () => {
      setLog((prev) => [
        ...prev,
        { type: "serial_rx", data: "Connected to serial relay", timestamp: Date.now() },
      ]);
    };

    ws.onclose = () => {
      setSerialConnected(false);
      setLog((prev) => [
        ...prev,
        { type: "serial_rx", data: "Disconnected from serial relay", timestamp: Date.now() },
      ]);
    };

    return () => {
      ws.close();
    };
  }, [phoneIp, phoneConnected]);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [log]);

  const send = () => {
    if (!input.trim() || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;

    const cmd = { action: "write", data: input + "\n" };
    wsRef.current.send(JSON.stringify(cmd));

    setLog((prev) => [
      ...prev,
      { type: "serial_tx", data: input, timestamp: Date.now() },
    ]);
    setInput("");
  };

  const clearLog = () => setLog([]);

  return (
    <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
      <div className="flex items-center justify-between mb-3">
        <div className="text-sm font-semibold tracking-wide">Serial Monitor</div>
        <div className="flex items-center gap-2">
          <span
            className={`w-2 h-2 rounded-full ${
              serialConnected ? "bg-green-400" : "bg-zinc-600"
            }`}
          />
          <span
            className={`text-xs font-semibold ${
              serialConnected ? "text-green-400" : "text-zinc-500"
            }`}
          >
            {serialConnected ? "ESP32 Connected" : "Disconnected"}
          </span>
        </div>
      </div>

      {/* Log output */}
      <div className="bg-black rounded border border-zinc-800 p-2 h-48 overflow-y-auto font-mono text-xs mb-3">
        {log.length === 0 && (
          <div className="text-zinc-600">No serial data yet...</div>
        )}
        {log.map((msg, i) => (
          <div key={i} className="flex gap-2">
            <span
              className={
                msg.type === "serial_tx"
                  ? "text-blue-400"
                  : msg.type === "serial_status"
                  ? "text-yellow-400"
                  : "text-green-400"
              }
            >
              {msg.type === "serial_tx" ? ">" : msg.type === "serial_status" ? "*" : "<"}
            </span>
            <span className="text-zinc-300 break-all">{msg.data}</span>
          </div>
        ))}
        <div ref={logEndRef} />
      </div>

      {/* Input */}
      <div className="flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && send()}
          placeholder='Send command (e.g., {"m":[100,100,100,100]})'
          className="flex-1 bg-zinc-900 border border-zinc-700 rounded px-2 py-1 text-sm font-mono"
          disabled={!serialConnected}
        />
        <button
          onClick={send}
          disabled={!serialConnected || !input.trim()}
          className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 rounded text-sm font-semibold disabled:opacity-50"
        >
          Send
        </button>
        <button
          onClick={clearLog}
          className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 rounded text-sm"
        >
          Clear
        </button>
      </div>
    </article>
  );
}
