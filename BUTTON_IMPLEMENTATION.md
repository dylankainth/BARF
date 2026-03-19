# Button Implementation - BARF Stack

This document describes how the physical button (GPIO D4) on the ESP32 robot is integrated throughout the entire BARF (Boring Android Robotics Framework) stack.

## Hardware Setup

- **Pin**: GPIO 4 (D4)
- **Connection**: Button pulls pin to 3.3V when pressed
- **Pull-down**: Internal pulldown resistor enabled (`INPUT_PULLDOWN`)
- **Debounce**: 50ms debounce filter

## Data Flow

```
ESPErino Button (D4)
    ↓
handleButtonInput() + debouncing
    ↓
WebSocket broadcast: "button:pressed" / "button:released"
    ↓
SimpleWebSocketServer.onMessage()
    ↓
SimpleWebSocketServer.ButtonEventCallback
    ↓
RhinoScriptExecutor.onButtonPressed() / onButtonReleased()
    ↓
Rhino.js Callback: onButtonPressed() / onButtonReleased()
    ↓
Your JavaScript Robot Script
```

## ESP32/Arduino Side

### File: `usingWebsockets.ino`

**Button Configuration:**
```cpp
const int BUTTON_PIN = 4;  // D4 with internal pulldown
const unsigned long DEBOUNCE_DELAY = 50;  // milliseconds
```

**Button Handling:**
```cpp
void handleButtonInput() {
    // Debounced button reading
    // Detects LOW->HIGH and HIGH->LOW transitions
}

void broadcastButtonEvent(const char* eventType) {
    // Broadcasts "button:pressed" or "button:released" to all WebSocket clients
}
```

**Integration in loop():**
```cpp
void loop() {
  webSocket.loop();     // Handle WebSocket traffic
  handleButtonInput();  // Handle button presses with debouncing
}
```

## Android Side

### 1. WebSocket Server: `SimpleWebSocketServer.java`

**New Interface:**
```java
public interface ButtonEventCallback {
    void onButtonPressed();
    void onButtonReleased();
}
```

**Message Handling:**
```java
@Override
public void onMessage(WebSocket conn, String message) {
    if (message.startsWith("button:")) {
        handleButtonEvent(message);  // Parse and forward to callback
        return;
    }
    // ... rest of message handling
}
```

### 2. HTTP Server: `SimpleHttpServer.java`

Registers button callbacks when starting the server:
```java
webSocketServer.setButtonEventCallback(new SimpleWebSocketServer.ButtonEventCallback() {
    @Override
    public void onButtonPressed() {
        scriptExecutor.onButtonPressed();
    }
    
    @Override
    public void onButtonReleased() {
        scriptExecutor.onButtonReleased();
    }
});
```

### 3. Script Executor: `RhinoScriptExecutor.java`

**Rhino.js Functions Available in Scripts:**
```javascript
onButtonPressed(callback)   // Register callback for button press
onButtonReleased(callback)  // Register callback for button release
```

**Internal Methods (called by SimpleHttpServer):**
```java
public void onButtonPressed()   // Trigger registered JS callback
public void onButtonReleased()  // Trigger registered JS callback
```

## Usage in Robot Scripts

### Basic Example

```javascript
// Register button event handlers
onButtonPressed(() => {
    log("Button was pressed!");
    playAudio("beep");
    move("forward", 0.5);
});

onButtonReleased(() => {
    log("Button was released!");
    stop();
});
```

### Complex Example with Detection

```javascript
let isAutoMoving = false;

onButtonPressed(() => {
    if (!isAutoMoving) {
        isAutoMoving = true;
        log("Starting auto-routine");
        playAudio("start");
        
        startYolo();
        sleep(1000);
        
        // Move based on detections
        move("forward", 0.7);
        sleep(2000);
        move("right", 0.5);
        sleep(1000);
        
        stop();
        playAudio("complete");
        isAutoMoving = false;
    }
});

onButtonReleased(() => {
    log("Emergency stop!");
    stop();
    isAutoMoving = false;
});
```

### Multiple Presses

```javascript
let pressCount = 0;

onButtonPressed(() => {
    pressCount++;
    log("Press count: " + pressCount);
    
    if (pressCount === 1) {
        playAudio("beep");
        move("forward", 0.5);
    } else if (pressCount === 2) {
        playAudio("alert");
        rotate("left", 0.5);
    } else if (pressCount >= 3) {
        log("Max presses reached");
        playAudio("error");
        pressCount = 0;
    }
});

onButtonReleased(() => {
    stop();
});
```

## Debouncing

The Arduino sketch implements debouncing to prevent false triggers:

- **Debounce Window**: 50ms
- **Logic**: Pin must be stable for 50ms before state change is registered
- **Benefits**: 
  - Prevents noise from causing multiple false triggers
  - Ensures clean, reliable button detection

## Debug Output

### Arduino Serial Monitor
```
Button PRESSED!
Broadcasted button event: button:pressed

Button RELEASED!
Broadcasted button event: button:released
```

### Android Logcat
```
SimpleWebSocketServer: Button event received: pressed
RhinoScriptExecutor: Button pressed event fired
SimpleHttpServer: Button pressed - forwarding to script

SimpleWebSocketServer: Button event received: released
RhinoScriptExecutor: Button released event fired
SimpleHttpServer: Button released - forwarding to script
```

## Notes

- Button events are only processed if a Rhino script is actively running
- Callbacks execute synchronously in the context thread
- Multiple button presses are queued and processed in order
- Maximum button frequency is limited by the ESP32 debounce filter (20+ Hz)

## Troubleshooting

### Button events not received in Rhino

1. Check ESP32 WebSocket connection (view in Logcat)
2. Verify `onButtonPressed()` and `onButtonReleased()` are registered in your script
3. Check for JavaScript errors in script execution logs

### Button triggering multiple times per press

Increase `DEBOUNCE_DELAY` in Arduino sketch (default 50ms is usually sufficient)

### false positives

- Ensure button pin is properly pulled down before pressing
- Check for noisy power supply noise near GPIO 4
- Verify 3.3V connection is stable

