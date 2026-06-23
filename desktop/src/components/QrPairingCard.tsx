import { useEffect, useState, useRef } from "react";
import QRCode from "qrcode";
import { invoke } from "@tauri-apps/api/core";

interface QrPairingCardProps {
  phoneIp: string;
  phoneStatus: string;
  onPair: (ip: string) => void;
}

export default function QrPairingCard({ phoneIp, phoneStatus, onPair }: QrPairingCardProps) {
  const [qrDataUrl, setQrDataUrl] = useState<string>("");
  const [desktopIp, setDesktopIp] = useState<string>("");
  const [pairError, setPairError] = useState<string>("");
  const [manualIp, setManualIp] = useState<string>("");
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // On mount: get desktop IP + pair key from Rust backend, generate QR
  useEffect(() => {
    invoke<string>("start_pairing")
      .then((result) => {
        try {
          const parsed = JSON.parse(result);
          setDesktopIp(parsed.desktop_ip);
          const qrContent = `barf://pair?ip=${parsed.desktop_ip}&key=${parsed.pair_key}&port=9876`;
          return QRCode.toDataURL(qrContent, {
            width: 256,
            margin: 2,
            color: { dark: "#000000", light: "#ffffff" },
          });
        } catch (e) {
          setPairError("Failed to parse pairing data");
          return Promise.reject(e);
        }
      })
      .then((dataUrl) => {
        if (dataUrl) setQrDataUrl(dataUrl);
      })
      .catch((e) => {
        if (!pairError) setPairError("Failed to generate QR: " + String(e));
      });
  }, []);

  // Poll for phone IP from QR pairing handshake
  useEffect(() => {
    pollingRef.current = setInterval(async () => {
      try {
        const result = await invoke<string>("get_paired_phone_ip");
        if (result && result !== "none") {
          onPair(result);
        }
      } catch {
        // ignore poll errors
      }
    }, 1000);
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [onPair]);

  const isPaired = Boolean(phoneIp);
  const isApiConnected = phoneStatus === "Connected";

  const handleManualConnect = () => {
    const ip = manualIp.trim();
    if (ip) onPair(ip);
  };

  const handleReset = () => {
    onPair("");
    setManualIp("");
  };

  return (
    <article className="mb-4 break-inside-avoid rounded-xl border border-[#22242b] bg-[linear-gradient(160deg,#131419_0%,#0f1014_100%)] p-4 text-zinc-100 shadow-[0_16px_40px_rgba(0,0,0,0.28)]">
      <div className="mb-3 text-sm font-semibold tracking-wide">Phone Connection</div>

      {isPaired ? (
        <div className="space-y-3">
          {/* Pairing confirmed row */}
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-green-400 flex-shrink-0" />
            <span className="text-green-400 text-sm font-semibold">Paired — {phoneIp}</span>
          </div>

          {/* API reachability row */}
          <div className="flex items-center gap-2">
            <span
              className={`w-2 h-2 rounded-full flex-shrink-0 ${
                isApiConnected ? "bg-green-400" : "bg-yellow-400 animate-pulse"
              }`}
            />
            <span className={`text-sm ${isApiConnected ? "text-green-400" : "text-yellow-400"}`}>
              {isApiConnected
                ? "API connected"
                : `Connecting to http://${phoneIp}:8080 …`}
            </span>
          </div>

          {/* Hint + exit button when stuck connecting */}
          {!isApiConnected && (
            <div className="space-y-2">
              <p className="text-xs text-zinc-500">
                Make sure the BARF app is open on the phone and both devices are on the same network (or Tailscale).
              </p>
              <button
                onClick={handleReset}
                className="w-full px-3 py-1.5 rounded-lg border border-zinc-700 bg-zinc-800 hover:bg-zinc-700 text-sm text-zinc-300 transition"
              >
                ← Try a different IP / re-scan QR
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-col items-center gap-3">
          {pairError ? (
            <div className="text-red-400 text-sm text-center">{pairError}</div>
          ) : qrDataUrl ? (
            <>
              <img
                src={qrDataUrl}
                alt="Pairing QR Code"
                className="w-48 h-48 border-2 border-zinc-600 rounded"
              />
              <p className="text-xs text-zinc-400 text-center">
                Scan this QR code with the phone's
                <br />
                "Pair with Desktop" option
              </p>
              <p className="text-xs text-zinc-500">Desktop IP: {desktopIp}</p>
            </>
          ) : (
            <div className="w-48 h-48 border-2 border-dashed border-zinc-700 rounded flex items-center justify-center">
              <span className="text-zinc-600 text-sm">Generating QR...</span>
            </div>
          )}

          {/* Manual IP entry */}
          <div className="w-full border-t border-zinc-800 pt-3 mt-1">
            <p className="text-xs text-zinc-500 mb-2 text-center">
              Or enter phone IP manually:
            </p>
            <div className="flex gap-2">
              <input
                type="text"
                value={manualIp}
                onChange={(e) => setManualIp(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleManualConnect()}
                className="flex-1 bg-zinc-800 border border-zinc-700 rounded px-2 py-1 text-sm font-mono"
                placeholder="192.168.x.x or 100.x.x.x"
              />
              <button
                onClick={handleManualConnect}
                disabled={!manualIp.trim()}
                className="px-3 py-1 bg-zinc-700 hover:bg-zinc-600 border border-zinc-600 rounded text-sm font-semibold disabled:opacity-40 transition"
              >
                Connect
              </button>
            </div>
          </div>
        </div>
      )}
    </article>
  );
}
