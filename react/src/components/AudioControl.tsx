import { useState, useEffect } from "react";
import { playAudio, stopAudio, getAvailableAudio } from "../lib/AudioManager";

export default function AudioControl() {
  const [audioFiles, setAudioFiles] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [selectedAudio, setSelectedAudio] = useState<string>("");
  const [playbackMessage, setPlaybackMessage] = useState<string>("");

  useEffect(() => {
    loadAudioFiles();
  }, []);

  const loadAudioFiles = async () => {
    setIsLoading(true);
    const files = await getAvailableAudio();
    setAudioFiles(files);
    if (files.length > 0) {
      setSelectedAudio(files[0]);
    }
    setIsLoading(false);
  };

  const handlePlayAudio = async () => {
    if (!selectedAudio) return;

    const result = await playAudio(selectedAudio);
    if (result.success) {
      setPlaybackMessage(`Playing: ${selectedAudio}`);
    } else {
      setPlaybackMessage(`Error: ${result.error || result.message}`);
    }
  };

  const handleStopAudio = async () => {
    const result = await stopAudio();
    if (result.success) {
      setPlaybackMessage("Stopped");
    } else {
      setPlaybackMessage(`Error: ${result.error || result.message}`);
    }
  };

  return (
    <div className="rounded-xl border border-[#3a3d44] bg-[linear-gradient(135deg,#1b1d23,#14161c)] p-4">
      <h3 className="text-lg font-semibold text-zinc-100 mb-4">Audio Player</h3>

      {isLoading ? (
        <p className="text-zinc-400">Loading audio files...</p>
      ) : audioFiles.length === 0 ? (
        <p className="text-zinc-400 text-sm">
          No audio files found. Add MP3 files to the audio folder.
        </p>
      ) : (
        <div className="flex flex-col gap-4">
          <select
            value={selectedAudio}
            onChange={(e) => setSelectedAudio(e.target.value)}
            className="rounded-lg border border-[#3a3d44] bg-[#14161c] px-3 py-2 text-zinc-100 text-sm focus:outline-none focus:border-zinc-500"
          >
            <option value="" disabled>
              Select an audio file
            </option>
            {audioFiles.map((file) => (
              <option key={file} value={file}>
                {file}
              </option>
            ))}
          </select>

          <div className="flex gap-3">
            <button
              onClick={handlePlayAudio}
              disabled={!selectedAudio}
              className="flex-1 rounded-lg border border-green-700 bg-green-900/20 px-4 py-2 text-sm font-semibold text-green-400 transition hover:bg-green-900/40 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Play
            </button>
            <button
              onClick={handleStopAudio}
              className="flex-1 rounded-lg border border-red-700 bg-red-900/20 px-4 py-2 text-sm font-semibold text-red-400 transition hover:bg-red-900/40"
            >
              Stop
            </button>
          </div>

          {playbackMessage && (
            <div className="text-sm text-zinc-400 bg-[#0f0f12] rounded px-3 py-2 border border-[#3a3d44]">
              {playbackMessage}
            </div>
          )}

          <button
            onClick={loadAudioFiles}
            className="text-xs text-zinc-500 hover:text-zinc-400 transition"
          >
            Refresh Audio List
          </button>
        </div>
      )}
    </div>
  );
}
