import React from "react";

type Props = {
  route: "control" | "settings" | "code";
  onNavigate: (r: "control" | "settings" | "code") => void;
};

const Nav: React.FC<Props> = ({ route, onNavigate }) => {


  return (
    <aside className="w-56 mr-6">
      <div className="sticky top-6 flex flex-col gap-4 p-4 rounded-xl bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] border border-[#22242b] shadow-[0_16px_40px_rgba(0,0,0,0.28)] h-[calc(100vh-64px)]">
        <div className="px-2">
          <h1 className="text-lg font-bold tracking-wide text-zinc-100">Robot</h1>
          <p className="text-xs text-zinc-400">Remote Console</p>
        </div>
        <nav className="mt-4 flex flex-col gap-2">
          <button onClick={() => onNavigate("control")} className={`text-left px-4 py-3 rounded-lg transition ${route === "control" ? 'bg-[#0f1620] text-emerald-200 font-semibold' : 'text-zinc-300 hover:bg-[#0f1014]'}`}>
            Control
          </button>
          <button onClick={() => onNavigate("code")} className={`text-left px-4 py-3 rounded-lg transition ${route === "code" ? 'bg-[#0f1620] text-emerald-200 font-semibold' : 'text-zinc-300 hover:bg-[#0f1014]'}`}>
            Code
          </button>
          <button onClick={() => onNavigate("settings")} className={`text-left px-4 py-3 rounded-lg transition ${route === "settings" ? 'bg-[#0f1620] text-emerald-200 font-semibold' : 'text-zinc-300 hover:bg-[#0f1014]'}`}>
            Settings
          </button>
        </nav>
        <div className="mt-auto px-2">
          <div className="text-xs text-zinc-500">v0.1</div>
        </div>
      </div>
    </aside>
  );
};

export default Nav;
