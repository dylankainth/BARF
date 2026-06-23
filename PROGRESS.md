# BARF — Progress Report
_Generated: 2026-06-11_

## Summary

Full-stack refactor of the BARF Android robotics platform, from a monolithic proof-of-concept to a structured, dev-mode-capable system with a Tauri desktop companion app, QR-based phone pairing, live MJPEG camera streaming, USB-serial communication to the ESP32, and an in-app serial monitor.

---

## Phase History

### Phase 0 — Repo restructure & cleanup _(2026-05-07)_
- Renamed `app/` → `android/`, changed package to `com.barf`
- Moved legacy `react/` web UI into `desktop/` (Tauri app root)
- Added `sdk/` with `barf.h` and `robot_firmware.h` reference headers
- Cleaned up `.gitignore`, removed stale screenshots and cruft

### Phase 1 — Decompose MainActivity god object _(2026-05-07)_
Split one ~1000-line `MainActivity.java` into focused packages:

| Package | Responsibility |
|---------|---------------|
| `camera/` | NDK Camera2 lifecycle, `VideoStreamManager` |
| `vision/` | YOLO + AprilTag per-frame pipeline |
| `serial/` | `UsbSerialManager` — USB Host CDC ACM |
| `runtime/` | `WasmRuntime` — WAMR JNI wrapper |
| `server/` | `PhoneApiServer` — HTTP/WebSocket API |
| `robot/` | `RobotController` — motor command logic |

### Phase 2 — WAMR JNI bridge _(2026-05-07)_
- Wrapped native globals (`g_yolo11`, `g_camera`, `g_apriltag`) into an `AppContext` struct
- Added `wasm_runtime.cpp` JNI bridge to the WAMR C API
- Integrated WASM `onFrame()` call into the per-frame pipeline in `yolo11ncnn.cpp`

### Phase 3 — Tauri desktop companion app _(2026-05-07)_
Built the desktop dev-mode app from scratch:
- **`compile_service.rs`** — invokes `arduino-cli` for ESP32 compilation and `clang` for WASM
- **`phone_bridge.rs`** — HTTP client connecting desktop to phone API for artifact deploy and serial relay
- **`serial_monitor.rs`** — serial port relay over WebSocket
- **React frontend** — WASM editor, ESP32 firmware editor, Dashboard page with camera feed placeholder

### Phase 4 — QR pairing _(2026-05-07 → 2026-06-04)_
- `PairingActivity` with ML Kit barcode scanner for QR-based pairing flow
- `WireGuardManager` stub for future VPN tunnel support
- `PairingManager` for persisting paired state
- `pairing_server.rs` on the desktop side to generate QR codes
- `QrPairingCard.tsx` component in the dashboard
- Fixed three bugs in the pairing round-trip (2026-06-04):
  - `parsePairingUri()` extracted and hardened; `NumberFormatException` caught instead of crashing
  - `isPaired()` early-exit removed; `PairingActivity` always shows the scanner
  - Success path changed from `startActivity(MainActivity)` → `setResult(RESULT_OK) + finish()` so `onActivityResult` fires correctly

### Phase 5 — Video streaming & serial monitor _(2026-06-03 → 2026-06-04)_
- `PhoneApiServer` exposes `GET /api/video` as a multipart MJPEG stream backed by `VideoStreamServer`
- Per-request daemon threads pump frames via `PipedOutputStream`; all stream threads interrupted on server shutdown (fixed pre-existing `stop()` infinite-recursion bug)
- Dashboard replaces static placeholder with `<img>` pointing at `http://<phoneIp>:8080/api/video` when paired
- `SerialMonitor.tsx` component added to desktop — bidirectional WebSocket relay to ESP32
- `UsbSerialManager` implemented — Android USB Host API, CDC ACM, JSON-newline read/write

### Phase 6 — Pairing stability & manifest fixes _(2026-06-09)_
Four bugs squashed in rapid succession:

1. **Manifest launcher bug** — `PairingActivity` was declared `LAUNCHER` + `singleTask`, placing it in a separate task from `MainActivity`. `onActivityResult` never fired; app appeared to crash on pair. Fixed: `MainActivity` is now `LAUNCHER` + `singleTop`; `PairingActivity` is `singleTop` non-exported.

2. **Camera race** — `ServerCallback.onStop()` shadowed `Activity.onStop()`, silently resetting `cameraClosedForPairing` mid-pairing and leaving the NDK camera open when CameraX tried to grab it. Renamed to `onRobotStop()`. `CameraHandoffState` extracted to its own class. 300 ms delay added before launching `PairingActivity` so NDK camera fully releases.

3. **Disconnect detection** — `SimpleWebSocketServer.ConnectionListener` added so `MainActivity` can surface a toast when the desktop disconnects.

### Phase 7 — Serial wired into RobotController + 30fps stream _(2026-06-09)_
- `RobotController` now accepts `UsbSerialManager` via `setUsbSerial()`
- Mecanum kinematics: `FL=y+x+r`, `FR=y-x-r`, `BL=y-x+r`, `BR=y+x-r`, clamped to ±255
- `sendCommand()` writes `{"m":[...]}` JSON over USB-serial; falls back to UDP when no device connected
- `MainActivity` promotes `usbSerialManager` to instance field, wires it in, handles `USB_DEVICE_ATTACHED` in `onNewIntent()`, disconnects in `onDestroy()`
- `VideoStreamManager`: capture interval 100 ms → 33 ms (30 fps), dedicated `HandlerThread` for `PixelCopy` callbacks, frames scaled to 640 px wide before encode, bitmaps recycled after encoding
- `VideoStreamServer`: JPEG quality 80 → 65, queue capacity 3 → 2, `submitFrame` made lock-free with `AtomicLong`

---

## Test Coverage Added

| Test class | What it covers |
|------------|---------------|
| `MainActivityUsbTest` | USB lifecycle wiring (53 cases) |
| `VideoStreamConfigTest` | 30fps config constants + threading (44 cases) |
| `CameraHandoffFlagTest` | `CameraHandoffState` lifecycle (39 cases) |
| `PairingUriParserTest` | URI parsing edge cases (null, malformed port, missing fields) |
| `ServerCallbackNamingTest` | `onRobotStop` shadow regression guard |
| `RobotControllerSerialTest` | Kinematics clamping + serial dispatch |
| `PhoneApiServerVideoTest` | MJPEG stream lifecycle and frame writing |
| `WebSocketDisconnectTest` | Disconnect listener callbacks |
| `Dashboard.test.tsx` | Desktop dashboard React component |

---

## Current State

- Android app builds and runs; phone pairs with desktop via QR scan
- Live MJPEG feed visible in the desktop dashboard at 30fps
- USB-serial to ESP32 is wired up end-to-end (mecanum JSON commands)
- Serial monitor in desktop reflects bidirectional serial traffic
- Pairing round-trip is stable (all known races and shadow bugs fixed)

## Next Up (from `next-step.txt`)

- Add YOLO / AprilTag runtime toggles (native flags + Dashboard buttons) to recover base FPS (~7 FPS → ~30 FPS with detection disabled)
- WireGuard NAT traversal for pairing outside local network
- App smoothing: loading states, retry, connection monitoring, offline mode (PLAN.md Phases 8–9)
