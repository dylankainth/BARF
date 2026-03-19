/**
 * AudioManager.ts
 * 
 * Utility for playing audio files via the Android HTTP server audio API.
 */

const API_BASE = "http://localhost:8080/api";

/**
 * Result from audio API operations
 */
export interface AudioResult {
  success: boolean;
  message: string;
  error?: string;
}

/**
 * Audio file information
 */
export interface AudioInfo {
  name: string;
  available: boolean;
}

/**
 * Play an audio file by name
 * @param audioName Name of the audio file (without extension)
 * @returns Promise resolving to the result
 */
export async function playAudio(audioName: string): Promise<AudioResult> {
  try {
    const response = await fetch(`${API_BASE}/audio/play`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ audio: audioName }),
    });

    const data = await response.json();
    return data;
  } catch (error) {
    return {
      success: false,
      message: "Failed to play audio",
      error: String(error),
    };
  }
}

/**
 * Stop audio playback
 * @returns Promise resolving to the result
 */
export async function stopAudio(): Promise<AudioResult> {
  try {
    const response = await fetch(`${API_BASE}/audio/stop`, {
      method: "POST",
    });

    const data = await response.json();
    return data;
  } catch (error) {
    return {
      success: false,
      message: "Failed to stop audio",
      error: String(error),
    };
  }
}

/**
 * Get list of available audio files
 * @returns Promise resolving to array of audio file names
 */
export async function getAvailableAudio(): Promise<string[]> {
  try {
    const response = await fetch(`${API_BASE}/audio/list`, {
      method: "GET",
    });

    const data = await response.json();
    if (data.success && data.audioFiles) {
      return data.audioFiles;
    }
    return [];
  } catch (error) {
    console.error("Failed to get audio list:", error);
    return [];
  }
}

/**
 * Play audio with callback
 * @param audioName Name of the audio file
 * @param onComplete Callback when audio starts playing
 */
export async function playAudioWithCallback(
  audioName: string,
  onComplete?: (result: AudioResult) => void
): Promise<void> {
  const result = await playAudio(audioName);
  if (onComplete) {
    onComplete(result);
  }
}

/**
 * Global function for easy access from JavaScript console or scripts
 */
declare global {
  interface Window {
    playAudio: (audioName: string) => Promise<AudioResult>;
    stopAudio: () => Promise<AudioResult>;
    getAvailableAudio: () => Promise<string[]>;
  }
}

// Attach to window for easy console access
if (typeof window !== "undefined") {
  window.playAudio = playAudio;
  window.stopAudio = stopAudio;
  window.getAvailableAudio = getAvailableAudio;
}

export default {
  playAudio,
  stopAudio,
  getAvailableAudio,
  playAudioWithCallback,
};
