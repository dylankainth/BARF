import { useState, useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

interface InstalledPlatform { id: string; installed: string; name: string; }
interface AvailablePlatform { id: string; latest: string; name: string; }
interface InstalledLibrary { name: string; version: string; location: string; }
interface AvailableLibrary { name: string; latest: string; author: string; sentence: string; }
interface ProgressEvt { line: string; done: boolean; error: boolean; }

const card = "rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]";

// ─── Additional URLs ──────────────────────────────────────────────────────────

function UrlsSection() {
  const [urls, setUrls] = useState<string[]>([]);
  const [newUrl, setNewUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  async function load() {
    try { setUrls(await invoke("get_additional_urls")); } catch (e: any) { setErr(String(e)); }
  }
  useEffect(() => { load(); }, []);

  async function add() {
    if (!newUrl.trim()) return;
    setBusy(true);
    try { await invoke("add_additional_url", { url: newUrl.trim() }); setNewUrl(""); await load(); }
    catch (e: any) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function remove(url: string) {
    setBusy(true);
    try { await invoke("remove_additional_url", { url }); await load(); }
    catch (e: any) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  return (
    <article className={card}>
      <div className="mb-3 text-sm font-semibold tracking-wide">Additional Board URLs</div>
      <div className="flex gap-2 mb-4">
        <input value={newUrl} onChange={e => setNewUrl(e.target.value)}
          onKeyDown={e => e.key === "Enter" && add()}
          placeholder="https://..."
          className="flex-1 bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm font-mono" />
        <button onClick={add} disabled={busy} className="btn">Add</button>
      </div>
      {err && <p className="text-xs text-red-400 mb-2">{err}</p>}
      {urls.length === 0 && <p className="text-xs text-zinc-500">No additional URLs configured.</p>}
      <ul className="space-y-2">
        {urls.map(url => (
          <li key={url} className="flex items-center justify-between gap-3 rounded-lg bg-zinc-800/50 px-3 py-2">
            <span className="text-xs font-mono text-zinc-300 break-all">{url}</span>
            <button onClick={() => remove(url)} disabled={busy}
              className="shrink-0 rounded border border-red-800/50 bg-red-900/20 px-2 py-0.5 text-xs text-red-400 hover:bg-red-900/40">
              Remove
            </button>
          </li>
        ))}
      </ul>
    </article>
  );
}

// ─── Board Manager ────────────────────────────────────────────────────────────

function BoardsSection() {
  const [installed, setInstalled] = useState<InstalledPlatform[]>([]);
  const [results, setResults] = useState<AvailablePlatform[]>([]);
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const [hasError, setHasError] = useState(false);
  const [activeOp, setActiveOp] = useState("");
  const unlistenRef = useRef<UnlistenFn | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const logRef = useRef<HTMLPreElement>(null);

  async function loadInstalled() {
    try { setInstalled(await invoke("list_installed_platforms")); } catch {}
  }
  useEffect(() => { loadInstalled(); }, []);

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [lines]);

  function handleQueryChange(q: string) {
    setQuery(q);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (q.length < 2) { setResults([]); return; }
    debounceRef.current = setTimeout(async () => {
      try { setResults(await invoke("search_platforms", { query: q })); } catch {}
    }, 400);
  }

  async function runOp(command: string, args: Record<string, unknown>, opLabel: string) {
    if (busy) return;
    setBusy(true);
    setLines([]);
    setHasError(false);
    setActiveOp(opLabel);

    const unlisten = await listen<ProgressEvt>("arduino-manager-progress", ev => {
      if (ev.payload.done) {
        setHasError(ev.payload.error);
        setBusy(false);
        setActiveOp("");
        unlisten();
        loadInstalled();
      } else if (ev.payload.line) {
        setLines(prev => [...prev, ev.payload.line]);
      }
    });
    unlistenRef.current = unlisten;

    try { await invoke(command, args); }
    catch (e: any) {
      setLines(prev => [...prev, String(e)]);
      setHasError(true);
      setBusy(false);
      setActiveOp("");
      unlisten();
    }
  }

  const installedIds = new Set(installed.map(p => p.id));

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <span className="text-sm text-zinc-400">{installed.length} platform(s) installed</span>
        <button onClick={() => runOp("update_core_index", {}, "Updating index...")} disabled={busy} className="btn">
          Update Index
        </button>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <article className={card}>
          <div className="mb-3 text-sm font-semibold tracking-wide">Installed</div>
          {installed.length === 0 && <p className="text-xs text-zinc-500">No platforms installed.</p>}
          <ul className="space-y-2">
            {installed.map(p => (
              <li key={p.id} className="flex items-center justify-between gap-2 rounded-lg bg-zinc-800/50 px-3 py-2">
                <div>
                  <div className="text-sm font-mono">{p.id}</div>
                  <div className="text-xs text-zinc-500">{p.name} · v{p.installed}</div>
                </div>
                <button onClick={() => runOp("uninstall_platform", { platform: p.id }, `Uninstalling ${p.id}...`)} disabled={busy}
                  className="shrink-0 rounded border border-red-800/50 bg-red-900/20 px-2 py-0.5 text-xs text-red-400 hover:bg-red-900/40">
                  Remove
                </button>
              </li>
            ))}
          </ul>
        </article>

        <article className={card}>
          <div className="mb-3 text-sm font-semibold tracking-wide">Search</div>
          <input value={query} onChange={e => handleQueryChange(e.target.value)}
            placeholder="Type to search platforms..."
            className="w-full bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm mb-3" />
          {results.length === 0 && query.length >= 2 && <p className="text-xs text-zinc-500">No results.</p>}
          {query.length < 2 && <p className="text-xs text-zinc-500">Type at least 2 characters to search.</p>}
          <ul className="space-y-2 max-h-72 overflow-y-auto">
            {results.map(p => (
              <li key={p.id} className="flex items-center justify-between gap-2 rounded-lg bg-zinc-800/50 px-3 py-2">
                <div>
                  <div className="text-sm font-mono">{p.id}</div>
                  <div className="text-xs text-zinc-500">{p.name} · v{p.latest}</div>
                </div>
                {installedIds.has(p.id)
                  ? <span className="text-xs text-green-500 shrink-0">Installed</span>
                  : <button onClick={() => runOp("install_platform", { platform: p.id }, `Installing ${p.id}...`)} disabled={busy}
                      className="shrink-0 btn text-xs px-2 py-0.5">Install</button>
                }
              </li>
            ))}
          </ul>
        </article>
      </div>

      {(lines.length > 0 || busy) && (
        <article className={`${card} ${hasError ? "border-red-900/40" : "border-zinc-700"}`}>
          <div className="flex justify-between items-center mb-2">
            <span className={`text-sm font-semibold ${hasError ? "text-red-400" : "text-zinc-400"}`}>
              {busy ? activeOp : hasError ? "Failed" : "Done"}
            </span>
            {!busy && <button onClick={() => setLines([])} className="text-xs text-zinc-500 hover:text-zinc-300">Clear</button>}
          </div>
          <pre ref={logRef} className="text-xs text-zinc-300 max-h-48 overflow-y-auto whitespace-pre-wrap">{lines.join("\n")}</pre>
        </article>
      )}
    </div>
  );
}

// ─── Library Manager ──────────────────────────────────────────────────────────

function LibrariesSection() {
  const [installed, setInstalled] = useState<InstalledLibrary[]>([]);
  const [results, setResults] = useState<AvailableLibrary[]>([]);
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const [hasError, setHasError] = useState(false);
  const [activeOp, setActiveOp] = useState("");
  const unlistenRef = useRef<UnlistenFn | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const logRef = useRef<HTMLPreElement>(null);

  async function loadInstalled() {
    try { setInstalled(await invoke("list_installed_libraries")); } catch {}
  }
  useEffect(() => { loadInstalled(); }, []);

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [lines]);

  function handleQueryChange(q: string) {
    setQuery(q);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (q.length < 2) { setResults([]); return; }
    debounceRef.current = setTimeout(async () => {
      try { setResults(await invoke("search_libraries", { query: q })); } catch {}
    }, 400);
  }

  async function runOp(command: string, args: Record<string, unknown>, opLabel: string) {
    if (busy) return;
    setBusy(true);
    setLines([]);
    setHasError(false);
    setActiveOp(opLabel);

    const unlisten = await listen<ProgressEvt>("arduino-manager-progress", ev => {
      if (ev.payload.done) {
        setHasError(ev.payload.error);
        setBusy(false);
        setActiveOp("");
        unlisten();
        loadInstalled();
      } else if (ev.payload.line) {
        setLines(prev => [...prev, ev.payload.line]);
      }
    });
    unlistenRef.current = unlisten;

    try { await invoke(command, args); }
    catch (e: any) {
      setLines(prev => [...prev, String(e)]);
      setHasError(true);
      setBusy(false);
      setActiveOp("");
      unlisten();
    }
  }

  const installedNames = new Set(installed.map(l => l.name));

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <span className="text-sm text-zinc-400">{installed.length} librar{installed.length === 1 ? "y" : "ies"} installed</span>
        <button onClick={() => runOp("update_library_index", {}, "Updating library index...")} disabled={busy} className="btn">
          Update Index
        </button>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <article className={card}>
          <div className="mb-3 text-sm font-semibold tracking-wide">Installed</div>
          {installed.length === 0 && <p className="text-xs text-zinc-500">No libraries installed.</p>}
          <ul className="space-y-2 max-h-96 overflow-y-auto">
            {installed.map(l => (
              <li key={l.name} className="flex items-center justify-between gap-2 rounded-lg bg-zinc-800/50 px-3 py-2">
                <div>
                  <div className="text-sm">{l.name}</div>
                  <div className="text-xs text-zinc-500">v{l.version} · {l.location}</div>
                </div>
                <button onClick={() => runOp("uninstall_library", { name: l.name }, `Uninstalling ${l.name}...`)} disabled={busy}
                  className="shrink-0 rounded border border-red-800/50 bg-red-900/20 px-2 py-0.5 text-xs text-red-400 hover:bg-red-900/40">
                  Remove
                </button>
              </li>
            ))}
          </ul>
        </article>

        <article className={card}>
          <div className="mb-3 text-sm font-semibold tracking-wide">Search</div>
          <input value={query} onChange={e => handleQueryChange(e.target.value)}
            placeholder="Search libraries..."
            className="w-full bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm mb-3" />
          {results.length === 0 && query.length >= 2 && <p className="text-xs text-zinc-500">No results.</p>}
          {query.length < 2 && <p className="text-xs text-zinc-500">Type at least 2 characters to search.</p>}
          <ul className="space-y-2 max-h-72 overflow-y-auto">
            {results.map(l => (
              <li key={l.name} className="flex items-center justify-between gap-2 rounded-lg bg-zinc-800/50 px-3 py-2">
                <div className="min-w-0">
                  <div className="text-sm">{l.name}</div>
                  <div className="text-xs text-zinc-500 truncate">{l.sentence}</div>
                  <div className="text-xs text-zinc-600">{l.author} · v{l.latest}</div>
                </div>
                {installedNames.has(l.name)
                  ? <span className="text-xs text-green-500 shrink-0">Installed</span>
                  : <button onClick={() => runOp("install_library", { name: l.name }, `Installing ${l.name}...`)} disabled={busy}
                      className="shrink-0 btn text-xs px-2 py-0.5">Install</button>
                }
              </li>
            ))}
          </ul>
        </article>
      </div>

      {(lines.length > 0 || busy) && (
        <article className={`${card} ${hasError ? "border-red-900/40" : "border-zinc-700"}`}>
          <div className="flex justify-between items-center mb-2">
            <span className={`text-sm font-semibold ${hasError ? "text-red-400" : "text-zinc-400"}`}>
              {busy ? activeOp : hasError ? "Failed" : "Done"}
            </span>
            {!busy && <button onClick={() => setLines([])} className="text-xs text-zinc-500 hover:text-zinc-300">Clear</button>}
          </div>
          <pre ref={logRef} className="text-xs text-zinc-300 max-h-48 overflow-y-auto whitespace-pre-wrap">{lines.join("\n")}</pre>
        </article>
      )}
    </div>
  );
}

// ─── Top-level export ─────────────────────────────────────────────────────────

export default function ArduinoManagerPage() {
  const [tab, setTab] = useState<"urls" | "boards" | "libraries">("urls");
  const tabs = [
    { key: "urls" as const, label: "Additional URLs" },
    { key: "boards" as const, label: "Board Manager" },
    { key: "libraries" as const, label: "Library Manager" },
  ];
  return (
    <div className="space-y-6">
      <div className="flex gap-2">
        {tabs.map(t => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className={`rounded-xl border px-4 py-2 text-sm font-semibold shadow-[0_12px_28px_rgba(0,0,0,0.35)] transition duration-200 ease-out hover:-translate-y-[1px] hover:cursor-pointer ${
              tab === t.key
                ? "bg-zinc-700 border-zinc-500 text-white"
                : "border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] text-zinc-100"
            }`}>
            {t.label}
          </button>
        ))}
      </div>
      {tab === "urls" && <UrlsSection />}
      {tab === "boards" && <BoardsSection />}
      {tab === "libraries" && <LibrariesSection />}
    </div>
  );
}
