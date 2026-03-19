# Audio Playback Feature

This project now supports playing MP3 and WAV audio files on the Android device's speaker through the HTTP API.

## Setup

### 1. Add Audio Files

Place your MP3 or WAV audio files in the `assets/audio/` folder:

```
app/src/main/assets/audio/
├── beep.mp3
├── alert.mp3
├── success.wav
└── ...
```

### 2. API Endpoints

The audio system provides three HTTP endpoints on port 8080:

#### Play Audio
```http
POST /api/audio/play
Content-Type: application/json

{
  "audio": "beep"
}
```

Response:
```json
{
  "success": true,
  "message": "Playing audio: beep"
}
```

#### Stop Audio
```http
POST /api/audio/stop
```

Response:
```json
{
  "success": true,
  "message": "Audio stopped"
}
```

#### List Available Audio
```http
GET /api/audio/list
```

Response:
```json
{
  "success": true,
  "audioFiles": ["beep", "alert", "success"],
  "count": 3
}
```

## Usage

### JavaScript/React

Import the AudioManager utility:

```typescript
import { playAudio, stopAudio, getAvailableAudio } from "./lib/AudioManager";

// Play audio
await playAudio("beep");

// Stop playback
await stopAudio();

// Get available audio files
const files = await getAvailableAudio();
console.log(files); // ["beep", "alert", "success"]
```

### Browser Console

Audio functions are exposed globally for easy testing:

```javascript
// Play audio from console
playAudio("beep");

// Stop playback
stopAudio();

// List available audio
getAvailableAudio().then(files => console.log(files));
```

### React Component

Use the `AudioControl` component in your React app:

```tsx
import AudioControl from "./components/AudioControl";

export function MyPage() {
  return (
    <div>
      <AudioControl />
    </div>
  );
}
```

### JavaScript Robot Script

In the robot control scripts, you can play audio:

```javascript
// Play a beep sound
playAudio("alert");

// Move robot and play sound
move("forward", 0.5);
sleep(2000);
playAudio("success");
stop();
```

## Audio Files Included

By default, the `assets/audio/` folder is empty. You need to add your own audio files.

### Recommended Audio Files

Common audio files to include:

- `beep.mp3` - Short notification beep
- `alert.mp3` - Warning alert sound
- `success.mp3` - Success/completion sound
- `error.mp3` - Error sound

You can use free online sources like:
- [Freesound.org](https://freesound.org/)
- [Zapsplat.com](https://www.zapsplat.com/)
- [Pixabay.com](https://pixabay.com/sound-effects/)

## Implementation Details

### Android Side

- **AudioPlayer.java**: Manages MP3/WAV playback using Android's MediaPlayer
- **SimpleHttpServer.java**: Provides HTTP API endpoints for audio control
- Audio files are loaded from `assets/audio/` on server startup
- Temporary files are used for playback and cleaned up automatically

### Web Side

- **AudioManager.ts**: TypeScript utility for API communication
- **AudioControl.tsx**: React component with UI for selecting and playing audio
- Global window functions for console access

## Persistence

Audio files persist across app restarts. The audio list is loaded when the server starts.

## Limitations

- Only one audio file can play at a time
- Previous playback is stopped when a new audio is requested
- Audio files must be named without spaces or special characters (use `.mp3` or `.wav` extension)
- Maximum recommended file size: 10MB per file

## Troubleshooting

### Audio files not appearing in the list

1. Make sure files are in `app/src/main/assets/audio/`
2. Ensure files are named with lowercase letters and `.mp3` or `.wav` extension
3. Rebuild and redeploy the app

### Audio won't play

1. Check device volume is not muted
2. Verify the audio file format (must be MP3 or WAV)
3. Check logcat for error messages: `AudioPlayer` tag

### Getting 404 error when calling API

1. Ensure the HTTP server is running (check in MainActivity logs)
2. Verify you're using the correct URL format
3. Check that the audio name doesn't include file extension
