import { useEffect, useState } from "react";
import Editor from "@monaco-editor/react";
import { invoke } from "@tauri-apps/api/core";

const DEFAULT_WASM = `#include "barf.h"

void setup() {
    log_info("WASM vision module started");
}

void on_frame(const char* detections_json) {
    // Parse detections_json, decide movement
    // Example: move forward if anything is detected
    // move("forward", 0.5);
}
`;

export default function VisionEditor() {
  const [source, setSource] = useState(DEFAULT_WASM);
  const [phoneIp, setPhoneIp] = useState("");
  const [status, setStatus] = useState<string>("");
  const [output, setOutput] = useState<string>("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    invoke<string>("get_paired_phone_ip")
      .then((ip) => { if (ip && ip !== "none") setPhoneIp(ip); })
      .catch(() => {});
  }, []);

  async function compileAndDeploy() {
    if (!phoneIp) {
      setStatus("No phone IP — pair a phone on the Dashboard first.");
      return;
    }
    setBusy(true);
    setStatus("Compiling...");
    setOutput("");
    try {
      const wasmBytes: number[] = await invoke("compile_wasm", { source });
      setStatus(`Compiled (${wasmBytes.length} bytes). Deploying...`);

      const resp: string = await invoke("deploy_wasm", { ip: phoneIp, wasmBytes });
      setStatus("Deployed. Phone response: " + resp);
    } catch (e: any) {
      const msg = typeof e === "string" ? e : e?.message ?? String(e);
      setStatus("Failed.");
      setOutput(msg);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <article className="rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
        <div className="mb-3 text-sm font-semibold tracking-wide">Vision Script (C++ → WASM)</div>
        <Editor height="50vh" defaultLanguage="cpp" value={source} onChange={(v) => setSource(v || "")} theme="vs-dark" />
      </article>

      <div className="flex gap-3 items-center flex-wrap">
        <input
          type="text"
          value={phoneIp}
          onChange={(e) => setPhoneIp(e.target.value)}
          className="bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm w-40 font-mono"
          placeholder="Phone IP (auto from pairing)"
        />
        <button onClick={compileAndDeploy} disabled={busy} className="btn">
          {busy ? "Working..." : "Compile & Deploy"}
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
