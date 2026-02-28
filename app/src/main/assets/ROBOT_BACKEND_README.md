# Robot Backend API Documentation

This Android backend provides HTTP/WebSocket APIs for controlling a robot via UDP and executing JavaScript FSM scripts. It integrates with a React dashboard frontend.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      React Dashboard                             │
│  (index.html served from assets/web/)                           │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                    HTTP/WebSocket
                              │
┌─────────────────────────────┴───────────────────────────────────┐
│                   Android Backend                                │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              RobotHttpServer (NanoHTTPD)                │    │
│  │  - POST /api/command                                    │    │
│  │  - POST /api/script                                     │    │
│  │  - GET  /api/status                                     │    │
│  │  - GET  /*  (static files)                              │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              │                                   │
│  ┌───────────────┐  ┌───────┴───────┐  ┌─────────────────┐     │
│  │ WebSocket     │  │ Script Engine │  │ UDP Controller  │     │
│  │ Handler       │  │ (Rhino JS)    │  │                 │     │
│  └───────┬───────┘  └───────────────┘  └────────┬────────┘     │
│          │                                       │               │
│          │ Telemetry                            │ Commands      │
│          ▼                                       ▼               │
└──────────────────────────────────────────────────────────────────┘
           │                                       │
           │                                       │
           ▼                                       ▼
    ┌──────────────┐                      ┌──────────────┐
    │   React      │                      │   ESP32      │
    │   Clients    │                      │   Robot      │
    └──────────────┘                      │  (UDP 4210)  │
                                          └──────────────┘
```

## API Endpoints

### POST /api/command

Send robot movement commands.

**Request:**
```json
{
  "x": 0,      // Strafe (-255 to 255)
  "y": 100,    // Forward/backward (-255 to 255)
  "r": 0,      // Rotation (-255 to 255)
  "e": 0       // Elevator (-255 to 255)
}
```

**Response:**
```json
{
  "success": true,
  "command": "RobotCommand{x=0, y=100, r=0, e=0}"
}
```

### POST /api/script

Execute JavaScript FSM script.

**Request:**
- Content-Type: `text/plain`
- Body: JavaScript code

**Example:**
```javascript
robot.forward(100);
delay(2000);
robot.turn(50);
delay(1000);
robot.stop();
```

**Response:**
```json
{
  "success": true,
  "message": "Script execution started"
}
```

### GET /api/status

Get current robot and server status.

**Response:**
```json
{
  "online": true,
  "robotConnected": true,
  "lastCommand": "{\"x\":0,\"y\":100,\"r\":0,\"e\":0}",
  "scriptRunning": false,
  "wsClients": 2,
  "robotHost": "192.168.4.1",
  "robotPort": 4210,
  "lastTelemetry": {
    "type": "command",
    "payload": {...},
    "timestamp": 1706450000000
  }
}
```

### POST /api/stop

Emergency stop - stops robot and terminates running scripts.

**Response:**
```json
{
  "success": true,
  "message": "Robot stopped and script terminated"
}
```

### GET/POST /api/config

Get or update robot configuration.

**GET Response:**
```json
{
  "robotHost": "192.168.4.1",
  "robotPort": 4210
}
```

**POST Request:**
```json
{
  "robotHost": "192.168.1.100",
  "robotPort": 4210
}
```

### WebSocket /ws

Real-time bidirectional communication.

**Message Format:**
```json
{
  "type": "detection",
  "payload": {
    "label": "person",
    "confidence": 0.95,
    "x": 320,
    "y": 240
  },
  "timestamp": 1706450000000
}
```

**Event Types:**
- `connected` - Client connected
- `command` - Robot command sent
- `detection` - YOLO detection
- `state_change` - FSM state change
- `script_started` - Script execution started
- `script_completed` - Script execution completed
- `script_log` - Script console output
- `error` - Error occurred
- `stopped` - Robot stopped
- `pong` - Response to ping

## JavaScript Script API

Scripts run in Mozilla Rhino and have access to these APIs:

### Robot Control

```javascript
// Movement
robot.forward(speed)      // speed: -255 to 255
robot.backward(speed)
robot.strafe(speed)       // Sideways movement
robot.turn(speed)         // Rotation
robot.turnLeft(speed)
robot.turnRight(speed)
robot.move(x, y, r, e)    // Full control
robot.stop()              // Stop all motors

// Elevator
robot.elevator(speed)
robot.elevatorUp(speed)
robot.elevatorDown(speed)
```

### Console

```javascript
console.log("Message")
console.error("Error")
console.warn("Warning")
console.info("Info")
```

### Telemetry

```javascript
// Broadcast events to WebSocket clients
telemetry.broadcast("custom_type", { data: 123 })
telemetry.stateChange("idle", "moving")
telemetry.detection("person", 0.95, 100, 200)
telemetry.error("Something went wrong")
```

### Timing

```javascript
delay(milliseconds)  // Pause execution
```

## Files Structure

```
app/src/main/java/com/tencent/yolo11ncnn/
├── MainActivity.java          # Main activity with server integration
├── YOLO11Ncnn.java           # YOLO native interface
└── robot/
    ├── RobotCommand.java      # Command data class
    ├── RobotHttpServer.java   # HTTP/WebSocket server
    ├── RobotScriptEngine.java # JavaScript engine
    ├── RobotServerManager.java # Server management helper
    ├── RobotServerService.java # Background service
    ├── TelemetryEvent.java    # Telemetry data class
    ├── UdpRobotController.java # UDP communication
    └── WebSocketHandler.java  # WebSocket management

app/src/main/assets/
├── web/
│   └── index.html            # React dashboard (built files go here)
└── scripts/
    └── example_fsm.js        # Example FSM script
```

## Integration Example

### Broadcasting YOLO Detections

```java
// In your YOLO detection callback
public void onDetection(String label, float confidence, float x, float y) {
    MainActivity activity = (MainActivity) getActivity();
    if (activity != null) {
        activity.onYoloDetection(label, confidence, x, y);
    }
}
```

### Custom Telemetry from Native Code

```java
// Anywhere in your app
RobotServerManager manager = ((MainActivity) context).getRobotServerManager();
manager.broadcastCustom("sensor_data", new SensorData(temperature, humidity));
```

## Configuration

### ESP32 Robot Settings

Default settings in `UdpRobotController.java`:
- Host: `192.168.4.1` (ESP32 AP mode)
- Port: `4210`

Change at runtime via API:
```bash
curl -X POST http://android-ip:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{"robotHost": "192.168.1.100", "robotPort": 4210}'
```

### Server Port

Default port is `8080`. Change in `RobotServerManager` constructor:
```java
robotServerManager = new RobotServerManager(this, 8888);
```

## Dependencies

Add to `app/build.gradle`:
```groovy
dependencies {
    implementation 'org.nanohttpd:nanohttpd:2.3.1'
    implementation 'org.nanohttpd:nanohttpd-websocket:2.3.1'
    implementation 'org.mozilla:rhino:1.7.14'
    implementation 'com.google.code.gson:gson:2.10.1'
}
```

## Permissions

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

Register service:
```xml
<service 
    android:name=".robot.RobotServerService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

## Troubleshooting

### Server not starting
- Check if port 8080 is available
- Verify INTERNET permission is granted
- Check logcat for errors

### WebSocket disconnecting
- Ensure device stays awake (FLAG_KEEP_SCREEN_ON)
- Check network stability
- WebSocket auto-reconnects after 3 seconds

### UDP commands not received by robot
- Verify ESP32 is on same network or AP mode
- Check robot IP address and port
- Test with UDP packet sniffer

### Script errors
- Check logcat for Rhino errors
- Verify syntax is ES5 compatible (no arrow functions, let/const)
- Use console.log for debugging
