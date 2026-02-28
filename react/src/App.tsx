import { useState, useEffect } from "react";
import "./app.css";
import ControlPage from "./pages/ControlPage";
import SettingsPage from "./pages/SettingsPage";
import CodePage from "./pages/CodePage";

export function App() {
	const [route, setRoute] = useState<"control" | "settings" | "code">("control");

	useEffect(() => {
		document.title = "Robot Console";
	}, []);

	return (

    <div className="min-h-screen bg-[radial-gradient(circle_at_15%_20%,rgba(255,255,255,0.03),transparent_35%),radial-gradient(circle_at_80%_0%,rgba(255,255,255,0.04),transparent_40%),#0f0f12] text-zinc-100">

      <main className="px-6 pb-16 pt-10">
        <header className="mx-auto max-w-6xl mb-6 flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
          <div className="flex items-center gap-6">
            <p className="text-[40px] font-semibold uppercase tracking-[0.1em] text-zinc-300">
              BARF
            </p>
            <div className="text-sm text-zinc-400 font-medium">Boring Android Robotics Framework</div>
          </div>
          <div className="flex items-center gap-3">
           
            <button
              className="rounded-xl border border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] px-4 py-2 text-sm font-semibold text-zinc-100 shadow-[0_12px_28px_rgba(0,0,0,0.35)] transition duration-200 ease-out hover:-translate-y-[1px] hover:border-[#3a3d44] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-60 hover:cursor-pointer"
              onClick={() => setRoute("control")}
            >
                Control
            </button>
            <button
              className="rounded-xl border border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] px-4 py-2 text-sm font-semibold text-zinc-100 shadow-[0_12px_28px_rgba(0,0,0,0.35)] transition duration-200 ease-out hover:-translate-y-[1px] hover:border-[#3a3d44] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-60 hover:cursor-pointer"
              onClick={() => setRoute("code")}
            >
              Code
            </button>

              <button
              className="rounded-xl border border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] px-4 py-2 text-sm font-semibold text-zinc-100 shadow-[0_12px_28px_rgba(0,0,0,0.35)] transition duration-200 ease-out hover:-translate-y-[1px] hover:border-[#3a3d44] disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-60 hover:cursor-pointer"
              onClick={() => setRoute("settings")}
            >
              Settings
            </button>
         
          </div>
        </header>

        <section className="mx-auto max-w-[90vw]">
          {route === "control" && <ControlPage />}
          {route === "code" && <CodePage />}
          {route === "settings" && <SettingsPage />}
        </section>

      </main>
    </div>
 


	);
}

export default App;