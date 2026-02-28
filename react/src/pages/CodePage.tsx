import { useEffect, useState, useRef } from "react";
import Editor from '@monaco-editor/react';

const getBase = () => {
  const host = window.location.hostname || "localhost";
  return `http://${host}:8080`;
};

export default function CodePage() {
  const [script, setScript] = useState<string>("// Write JS for Rhino here\n");
  const [running, setRunning] = useState(false);
  const [output, setOutput] = useState<string>("");
  const saveTimer = useRef<number | null>(null);

  useEffect(() => {
    loadScript();
    const iv = setInterval(fetchStatus, 1000);
    return () => clearInterval(iv);
  }, []);

  async function loadScript() {
    try {
      const res = await fetch(`${getBase()}/api/script`);
      const json = await res.json();
      if (json && json.success) setScript(json.script || "");
    } catch (e) {}
  }

  async function saveScriptDebounced(next: string) {
    setScript(next);
    if (saveTimer.current) window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => saveScript(next), 800);
  }

  async function saveScript(data?: string) {
    try {
      await fetch(`${getBase()}/api/script`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ script: data ?? script }) });
    } catch (e) {}
  }

  async function runScript() {
    try {
      setOutput("");
      await fetch(`${getBase()}/api/script/run`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ script }) });
    } catch (e) {}
  }

  async function stopScript() {
    try {
      await fetch(`${getBase()}/api/script/stop`, { method: "POST" });
    } catch (e) {}
  }

  async function fetchStatus() {
    try {
      const res = await fetch(`${getBase()}/api/script/status`);
      const json = await res.json();
      if (json) {
        setRunning(!!json.running);
        if (json.output) setOutput(json.output);
      }
    } catch (e) {}
  }

  return (
    <div className="space-y-6">
     

      <section className="space-y-4 columns-1 md:columns-2 xl:columns-3">
          <article
            className="mt-6 mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]"
            style={{ columnSpan: 'all' }}
          >
            <div className="mb-3 text-sm font-semibold tracking-wide">Code Editor</div>

                    <Editor 
                      height="60vh" 
                      defaultLanguage="javascript" 
                      defaultValue={script} 
                      theme="vs-dark"
                      onChange={(value) => saveScriptDebounced(value || '')} 
                    />

           
          </article>

        

          <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
            <div className="mb-3 text-sm font-semibold tracking-wide">Test and Run</div>
            
             <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <button
                onClick={runScript} disabled={running}
                className="rounded-xl border border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] px-4 py-2 text-sm font-semibold text-zinc-100 shadow-[0_12px_28px_rgba(0,0,0,0.35)] transition duration-200 ease-out hover:-translate-y-[1px] hover:border-[#3a3d44] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-60 hover:cursor-pointer"
                >
                Run
                </button>

                 <button
                onClick={stopScript} disabled={!running}
                className="rounded-xl border border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] px-4 py-2 text-sm font-semibold text-zinc-100 shadow-[0_12px_28px_rgba(0,0,0,0.35)] transition duration-200 ease-out hover:-translate-y-[1px] hover:border-[#3a3d44] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-60 hover:cursor-pointer"
                >
                Stop
                </button>

                 <button
                onClick={() => saveScript()}
                className="rounded-xl border border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] px-4 py-2 text-sm font-semibold text-zinc-100 shadow-[0_12px_28px_rgba(0,0,0,0.35)] transition duration-200 ease-out hover:-translate-y-[1px] hover:border-[#3a3d44] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-60 hover:cursor-pointer"
                >
                Save
                </button>


            </div>
          
          </article>

     
          <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)] min-h-[260px]">
            <div className="mb-3 text-sm font-semibold tracking-wide">Logs</div>

            <div className={`mt-4 p-3 rounded border ${output ? "border-destructive" : "border-[color:var(--popover-foreground)]"}`}>
                <pre className="text-sm text-[color:var(--primary-foreground)] max-h-40 overflow-y-auto">{output || "(no output)"}</pre>
            </div>
           
          </article>


        </section>
    </div>
  );
}
