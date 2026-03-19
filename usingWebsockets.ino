#include <WiFi.h>
#include <WebSocketsServer.h> // Replaces WebServer and WiFiUdp
#include <ArduinoJson.h>      // Still included for library compatibility, though not used directly

// --- Network Configuration ---
const char* ssid = "KCL_ICE_CREAM";
const char* password = "mryipfromreading";

// --- WebSocket Configuration ---
const unsigned int wsPort = 4210; // Standard port for robot commands
WebSocketsServer webSocket = WebSocketsServer(wsPort);

// --- Mecanum Drive Pin Assignments (ESP32 -> IBT-2 / BTS7960) ---
// Each motor uses two PWM inputs:
//   - RPWM: drive in the "forward" direction
//   - LPWM: drive in the "reverse" direction
// IMPORTANT: Never drive both RPWM and LPWM with non-zero PWM at the same time.
// Front Left
const int FL_RPWM = 12;
const int FL_LPWM = 13;
// Front Right
const int FR_RPWM = 27;
const int FR_LPWM = 14;
// Back Left
const int BL_RPWM = 25;
const int BL_LPWM = 26;
// Back Right
const int BR_RPWM = 32;
const int BR_LPWM = 33;

// --- Elevator Motor Pin Assignments (IBT-2 / BTS7960) ---
// Motor A (Left Side)
const int M_A_RPWM = 19; // Motor A RPWM (UP)
const int M_A_LPWM = 18; // Motor A LPWM (DOWN)
// Motor B (Right Side)
const int M_B_RPWM = 16; // Motor B RPWM (UP)
const int M_B_LPWM = 17; // Motor B LPWM (DOWN)

// --- Button Pin Assignments ---
const int BUTTON_PIN = 4;  // D4 - Button connected to 3.3V with internal pulldown

// --- Button Debouncing ---
const unsigned long DEBOUNCE_DELAY = 50;  // milliseconds
volatile bool buttonPressed = false;
unsigned long lastDebounceTime = 0;
bool lastButtonState = false;
bool currentButtonState = false;

// --- Function Declarations ---
void connectToWiFi();
void webSocketEvent(uint8_t num, WStype_t type, uint8_t * payload, size_t length);
void setMotorIBT2(int rpwmPin, int lpwmPin, int speed);
void mecanumDrive(int x, int y, int rotation);
void controlElevator(int speed);
void handleButtonInput();
void broadcastButtonEvent(const char* eventType);

void setup() {
  Serial.begin(115200);
  Serial.println("Robot Control Initializing...");

  // 1. Initialize Mecanum Motor Pins
  pinMode(FL_RPWM, OUTPUT); pinMode(FL_LPWM, OUTPUT);
  pinMode(FR_RPWM, OUTPUT); pinMode(FR_LPWM, OUTPUT);
  pinMode(BL_RPWM, OUTPUT); pinMode(BL_LPWM, OUTPUT);
  pinMode(BR_RPWM, OUTPUT); pinMode(BR_LPWM, OUTPUT);

  // 2. Initialize Elevator Motor Pins
  pinMode(M_A_RPWM, OUTPUT);
  pinMode(M_A_LPWM, OUTPUT);
  pinMode(M_B_RPWM, OUTPUT);
  pinMode(M_B_LPWM, OUTPUT);

  // 3. Initialize Button Pin with Internal Pulldown
  pinMode(BUTTON_PIN, INPUT_PULLDOWN);
  Serial.println("Button on D4 configured with internal pulldown.");

  // Initialize all systems to stop
  mecanumDrive(0, 0, 0);
  controlElevator(0);

  // Connect to Wi-Fi
  connectToWiFi();

  // Configure WebSocket Server (Port 4210)
  webSocket.begin();
  webSocket.onEvent(webSocketEvent);
  Serial.printf("WebSocket server started on port %u.\n", wsPort);
  Serial.println("Ready for WebSocket commands (X,Y,R,E).");
}

void loop() {
  webSocket.loop(); // Handle WebSocket traffic
  handleButtonInput(); // Handle button presses with debouncing
}

// --- WebSocket Event Handler ---

void webSocketEvent(uint8_t num, WStype_t type, uint8_t * payload, size_t length) {
  switch(type) {
    case WStype_DISCONNECTED:
      Serial.printf("[%u] Disconnected!\n", num);
      // Safety feature: Stop motors if connection drops
      mecanumDrive(0, 0, 0);
      controlElevator(0);
      break;
      
    case WStype_CONNECTED:
      {
        IPAddress ip = webSocket.remoteIP(num);
        Serial.printf("[%u] Connected from %d.%d.%d.%d url: %s\n", num, ip[0], ip[1], ip[2], ip[3], payload);
      }
      break;
      
    case WStype_TEXT:
      {
        Serial.println("========================================");
        Serial.printf("WS Received: %s\n", payload);

        // Protocol: X,Y,R,E (comma separated values)
        int x, y, r, e;
        int itemsRead = sscanf((const char *)payload, "%d,%d,%d,%d", &x, &y, &r, &e);

        if (itemsRead == 4) {
          // Clamp inputs to expected ranges
          x = constrain(x, -255, 255);
          y = constrain(y, -255, 255);
          r = constrain(r, -255, 255);
          e = constrain(e, -255, 255);

          // Execute commands
          mecanumDrive(x, y, r);
          controlElevator(e);
        } else {
          Serial.printf("WS Parsing Error: Expected 4 values, got %d\n", itemsRead);
        }
        Serial.println("========================================");
      }
      break;
      
    case WStype_BIN:
    case WStype_ERROR:
    case WStype_FRAGMENT_TEXT_START:
    case WStype_FRAGMENT_BIN_START:
    case WStype_FRAGMENT:
    case WStype_FRAGMENT_FIN:
      // Unused for this application
      break;
  }
}

// --- Mecanum Drive Motor Control Functions ---

/**
 * @brief Sets the speed and direction for a single motor using two pins.
 * @param rpwmPin The PWM pin for the positive direction (RPWM).
 * @param lpwmPin The PWM pin for the negative direction (LPWM).
 * @param speed The speed (-255 to 255).
 */
void setMotorIBT2(int rpwmPin, int lpwmPin, int speed) {
  int absSpeed = abs(speed);

  if (speed > 0) {
    // Forward
    analogWrite(rpwmPin, absSpeed);
    analogWrite(lpwmPin, 0);
    // Added specific pin output logging
    Serial.printf("   [IBT-2 Motor] RPWM/LPWM %d/%d: FORWARD at %d\n", rpwmPin, lpwmPin, absSpeed);
  } else if (speed < 0) {
    // Backward
    analogWrite(rpwmPin, 0);
    analogWrite(lpwmPin, absSpeed);
    // Added specific pin output logging
    Serial.printf("   [IBT-2 Motor] RPWM/LPWM %d/%d: REVERSE at %d\n", rpwmPin, lpwmPin, absSpeed);
  } else {
    // Stop
    analogWrite(rpwmPin, 0);
    analogWrite(lpwmPin, 0);
    // Added specific pin output logging
    Serial.printf("   [IBT-2 Motor] RPWM/LPWM %d/%d: STOP\n", rpwmPin, lpwmPin);
  }
}

/**
 * @brief Calculates and executes Mecanum wheel speeds based on X, Y, and Rotation.
 * @param x Strafe command (-255 to 255).
 * @param y Forward/Backward command (-255 to 255).
 * @param rotation Rotation command (-255 to 255).
 */
void mecanumDrive(int x, int y, int rotation) {
  // Calculate raw speeds
  int fl_raw = y + x + rotation;
  int fr_raw = y - x - rotation;
  int bl_raw = y - x + rotation;
  int br_raw = y + x - rotation;

  // Constrain final speeds to valid PWM range
  int fl = constrain(fl_raw, -255, 255);
  int fr = constrain(fr_raw, -255, 255);
  int bl = constrain(bl_raw, -255, 255);
  int br = constrain(br_raw, -255, 255);

  // Debug output for Mecanum calculation
  Serial.printf("Mecanum Input: X=%d, Y=%d, R=%d\n", x, y, rotation);
  Serial.printf("Mecanum Output (FL, FR, BL, BR): %d, %d, %d, %d\n", fl, fr, bl, br);

  setMotorIBT2(FL_RPWM, FL_LPWM, fl);
  setMotorIBT2(FR_RPWM, FR_LPWM, fr);
  setMotorIBT2(BL_RPWM, BL_LPWM, bl);
  setMotorIBT2(BR_RPWM, BR_LPWM, br);
}

// --- Elevator Control Function ---

/**
 * @brief Controls two independent motors simultaneously for synchronized linear movement.
 * @param speed The speed of the elevator (0-255).
 * Positive speed moves the elevator UP, negative speed moves it DOWN.
 * 0 stops the elevator.
 */
void controlElevator(int speed) {
  // Ensure speed is within the valid range
  int constrainedSpeed = constrain(speed, -255, 255);
  int absSpeed = abs(constrainedSpeed);

  Serial.printf("Elevator Input Speed: %d (Constrained: %d)\n", speed, constrainedSpeed);

  // UP Movement (Positive Speed)
  if (constrainedSpeed > 0) {
    // Motors A and B: Set to forward (UP)
    analogWrite(M_A_RPWM, absSpeed); analogWrite(M_A_LPWM, 0);
    // Motors B: Set to forward (UP)
    analogWrite(M_B_RPWM, absSpeed); analogWrite(M_B_LPWM, 0);

    // Added detailed logging for elevator pins
    Serial.printf("Elevator Action: Moving UP. PWM=%d (Pins A RPWM:%d, B RPWM:%d)\n", absSpeed, M_A_RPWM, M_B_RPWM);
  }
  // DOWN Movement (Negative Speed)
  else if (constrainedSpeed < 0) {
    // Motors A and B: Set to backward (DOWN)
    analogWrite(M_A_RPWM, 0); analogWrite(M_A_LPWM, absSpeed);
    analogWrite(M_B_RPWM, 0); analogWrite(M_B_LPWM, absSpeed);

    // Added detailed logging for elevator pins
    Serial.printf("Elevator Action: Moving DOWN. PWM=%d (Pins A LPWM:%d, B LPWM:%d)\n", absSpeed, M_A_LPWM, M_B_LPWM);
  }
  // STOP Movement (Zero Speed)
  else {
    // Stop Motor A and Motor B
    analogWrite(M_A_RPWM, 0); analogWrite(M_A_LPWM, 0);
    analogWrite(M_B_RPWM, 0); analogWrite(M_B_LPWM, 0);

    Serial.println("Elevator Action: STOPPED");
  }
}

// --- Wi-Fi Handler ---

void connectToWiFi() {
  Serial.print("Connecting to ");
  Serial.println(ssid);
  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("");
  Serial.println("WiFi connected");
  Serial.print("IP Address: ");
  Serial.println(WiFi.localIP());
}

// --- Button Handling with Debouncing ---

/**
 * @brief Handles button input with debouncing.
 * Detects button press (LOW to HIGH transition) and broadcasts the event.
 */
void handleButtonInput() {
  bool reading = digitalRead(BUTTON_PIN);
  
  // If the button state has changed, reset the debounce timer
  if (reading != lastButtonState) {
    lastDebounceTime = millis();
  }
  
  // If enough time has passed since the last change, consider the button state stable
  if ((millis() - lastDebounceTime) > DEBOUNCE_DELAY) {
    if (reading != currentButtonState) {
      currentButtonState = reading;
      
      // Button state changed after debounce period
      if (currentButtonState == HIGH) {
        // Button pressed (transition from LOW to HIGH)
        Serial.println("Button PRESSED!");
        broadcastButtonEvent("pressed");
      } else {
        // Button released (transition from HIGH to LOW)
        Serial.println("Button RELEASED!");
        broadcastButtonEvent("released");
      }
    }
  }
  
  lastButtonState = reading;
}

/**
 * @brief Broadcasts a button event to all connected WebSocket clients.
 * @param eventType "pressed" or "released"
 */
void broadcastButtonEvent(const char* eventType) {
  // Format: button:EVENT_TYPE
  String message = String("button:") + String(eventType);
  
  // Broadcast to all connected clients
  for (uint8_t i = 0; i < webSocket.connectedClients(); i++) {
    webSocket.sendTXT(i, (uint8_t*)message.c_str(), message.length());
  }
  
  Serial.printf("Broadcasted button event: %s\n", message.c_str());
}