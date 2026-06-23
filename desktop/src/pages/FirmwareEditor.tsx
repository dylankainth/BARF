import { useEffect, useState } from "react";
import Editor from "@monaco-editor/react";
import { invoke } from "@tauri-apps/api/core";

const DEFAULT_FIRMWARE = `#include "robot_firmware.h"

const int MOTOR_FL_PWM = 32;
const int MOTOR_FR_PWM = 33;
const int MOTOR_BL_PWM = 25;
const int MOTOR_BR_PWM = 26;

void setup() {
    Serial.begin(115200);
    ledcSetup(0, 5000, 8);
    ledcSetup(1, 5000, 8);
    ledcSetup(2, 5000, 8);
    ledcSetup(3, 5000, 8);
    ledcAttachPin(MOTOR_FL_PWM, 0);
    ledcAttachPin(MOTOR_FR_PWM, 1);
    ledcAttachPin(MOTOR_BL_PWM, 2);
    ledcAttachPin(MOTOR_BR_PWM, 3);
}

void loop() {
    if (Serial.available()) {
        String line = Serial.readStringUntil('\\n');
        // Parse {"m":[fl,fr,bl,br]} and set PWMs
    }
}
`;

export default function FirmwareEditor() {
  const [source, setSource] = useState(DEFAULT_FIRMWARE);
  const [status, setStatus] = useState<string>("");
  const [output, setOutput] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [compiled, setCompiled] = useState(false);
  const [ports, setPorts] = useState<string[]>([]);
  const [selectedPort, setSelectedPort] = useState<string>("");

  useEffect(() => {
    refreshPorts();
  }, []);

  async function refreshPorts() {
    try {
      const list: string[] = await invoke("list_serial_ports");
      setPorts(list);
      if (list.length > 0 && !selectedPort) setSelectedPort(list[0]);
    } catch {
      // arduino-cli not available, ignore
    }
  }

  async function compile() {
    setBusy(true);
    setStatus("Compiling...");
    setOutput("");
    setCompiled(false);
    try {
      const binPath: string = await invoke("compile_esp32", { source });
      setStatus("Compiled. Binary: " + binPath);
      setOutput("");
      setCompiled(true);
    } catch (e: any) {
      const msg = typeof e === "string" ? e : e?.message ?? String(e);
      setStatus("Compilation failed.");
      setOutput(msg);
    } finally {
      setBusy(false);
    }
  }

  async function flash() {
    if (!selectedPort) {
      setStatus("Select a serial port first.");
      return;
    }
    setBusy(true);
    setStatus(`Flashing to ${selectedPort}...`);
    setOutput("");
    try {
      const result: string = await invoke("flash_esp32", { port: selectedPort });
      setStatus(result);
    } catch (e: any) {
      const msg = typeof e === "string" ? e : e?.message ?? String(e);
      setStatus("Flash failed.");
      setOutput(msg);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <article className="rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
        <div className="mb-3 text-sm font-semibold tracking-wide">Firmware (Arduino C++ → ESP32)</div>
        <Editor height="50vh" defaultLanguage="cpp" value={source} onChange={(v) => setSource(v || "")} theme="vs-dark" />
      </article>

      <div className="flex gap-3 items-center flex-wrap">
        <button onClick={compile} disabled={busy} className="btn">
          {busy && !compiled ? "Compiling..." : "Compile"}
        </button>

        <div className="flex gap-2 items-center">
          <select
            value={selectedPort}
            onChange={(e) => setSelectedPort(e.target.value)}
            className="bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm font-mono"
          >
            {ports.length === 0 && <option value="">No ports found</option>}
            {ports.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
          <button
            onClick={refreshPorts}
            disabled={busy}
            className="rounded border border-zinc-700 bg-zinc-800 px-2 py-1 text-xs text-zinc-400 hover:text-zinc-200"
          >
            ↺
          </button>
        </div>

        <button onClick={flash} disabled={busy || !compiled} className="btn">
          {busy && compiled ? "Flashing..." : "Flash to ESP32"}
        </button>

        <span className="text-sm text-zinc-400">{status}</span>
      </div>

      {output && (
        <article className="rounded-xl border border-red-900/40 bg-[linear-gradient(160deg,#1a0f0f_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
          <div className="mb-2 text-sm font-semibold tracking-wide text-red-400">Compiler Output</div>
          <pre className="text-xs text-zinc-300 max-h-48 overflow-y-auto whitespace-pre-wrap">{output}</pre>
        </article>
      )}
    </div>
  );
}
