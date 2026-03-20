// 10.104.125.167
// Configuration & Constants
var PING_PONG_LABEL = 32;
var CENTER_X = 0.5; // Assuming normalized YOLO coordinates (0.0 to 1.0)
var TARGET_CENTER_TOLERANCE = 0.1; // Margin of error to consider ball "centered"
var SIGHT_TIMEOUT_MS = 500; // How long before considering the ball "lost"

// Speed Configurations
var ROTATE_SPEED_MULTIPLIER = 0.65; 
var SEARCH_ROTATE_SPEED = 0.3; 
var TRACK_FORWARD_SPEED = 0.2; 
var APPROACH_FORWARD_SPEED = 0.4; 
var CAPTURE_FORWARD_SPEED = 0.5; 

// Network Optimization Constants
var DRIVE_RATE_LIMIT_MS = 30; // Enforce minimum 30ms between sends
var DRIVE_EPSILON = 0.02; // Minimum change required to trigger a new command

// States
var STATE_SEARCH_BALL = 'SEARCH_BALL';
var STATE_TRACK_BALL = 'TRACK_BALL';
var STATE_APPROACH_BALL = 'APPROACH_BALL';
var STATE_CAPTURE_DRIVE = 'CAPTURE_DRIVE';

// Global Variables
var currentState = STATE_SEARCH_BALL;
var lastSeenTime = 0;
var ballX = 0;
var ballY = 0;
var captureStartTime = 0;

// PID Variables
var kp = 1.2;
var ki = 0.0;
var kd = 0.4;
var previousError = 0;
var integral = 0;

// Command Caching Variables
var lastSentX = null;
var lastSentY = null;
var lastSentRot = null;
var lastCommandTime = 0;

// --- Network Optimized Drive Function ---
function smartDrive(x, y, rot) {
    var now = Date.now();
    
    // 1. Enforce strict rate limit
    if (now - lastCommandTime < DRIVE_RATE_LIMIT_MS) {
        return; 
    }

    // 2. Check if the change is significant enough to warrant a network packet
    var xChanged = lastSentX === null || Math.abs(x - lastSentX) > DRIVE_EPSILON;
    var yChanged = lastSentY === null || Math.abs(y - lastSentY) > DRIVE_EPSILON;
    var rotChanged = lastSentRot === null || Math.abs(rot - lastSentRot) > DRIVE_EPSILON;

    // 3. Send command and update cache if thresholds are met
    if (xChanged || yChanged || rotChanged) {
        drive(x, y, rot);
        lastSentX = x;
        lastSentY = y;
        lastSentRot = rot;
        lastCommandTime = now;
    }
}

// Update tracking data when YOLO detects objects
onDetection(function(dets) {
    if (!dets) return;
    
    var yolodets = dets.get("yolo");
    if (!yolodets) return;

    var bestBall = null;
    var maxArea = 0;

    // Iterate through detections to find the largest (closest) ping pong ball
    for (var i = 0; i < Math.trunc(yolodets.size()); i++) {
        var d = yolodets.get(i);
        if (Math.trunc(d.get("label")) === PING_PONG_LABEL) {
            var w = d.get("w");
            var h = d.get("h");
            var area = w * h;
            
            if (area > maxArea) {
                maxArea = area;
                bestBall = { x: d.get("x"), y: d.get("y") };
            }
        }
    }

    // If we found a ball, update our tracking variables
    if (bestBall) {
        ballX = bestBall.x;
        ballY = bestBall.y;
        lastSeenTime = Date.now();
    }
});

// Helper function to calculate PID rotation
function calculatePIDRotation(currentX) {
    var error = currentX - CENTER_X; 
    integral += error;
    var derivative = error - previousError;
    previousError = error;
    
    var rotationSpeed = (kp * error) + (ki * integral) + (kd * derivative);
    
    // Clamp the rotation speed between -1.0 and 1.0
    return Math.max(-1.0, Math.min(1.0, rotationSpeed));
}

// Main State Machine Loop
stopAprilTag();
startYolo();

while (true) {
    try {
        var now = Date.now();
        var timeSinceLastSeen = now - lastSeenTime;
        var ballVisible = (timeSinceLastSeen < SIGHT_TIMEOUT_MS);

        switch (currentState) {
            
            case STATE_SEARCH_BALL:
                // Action: Rotate slowly scanning for ping pong balls
                smartDrive(0, 0, SEARCH_ROTATE_SPEED * ROTATE_SPEED_MULTIPLIER); 
                
                // Transition: ball_detected
                if (ballVisible) {
                    log("Ball detected! Transitioning to TRACK_BALL.");
                    currentState = STATE_TRACK_BALL;
                    integral = 0; // Reset PID on new track
                    previousError = 0;
                }
                break;

            case STATE_TRACK_BALL:
                // Transition: ball_lost
                if (!ballVisible) {
                    log("Ball lost. Transitioning to SEARCH_BALL.");
                    currentState = STATE_SEARCH_BALL;
                    break;
                }

                // Action: PID steering using ball_x while slowly driving forward
                var rotation = calculatePIDRotation(ballX);
                smartDrive(0, TRACK_FORWARD_SPEED, rotation * ROTATE_SPEED_MULTIPLIER); 

                // Transition: ball_centered
                if (Math.abs(ballX - CENTER_X) < TARGET_CENTER_TOLERANCE) {
                    log("Ball centered! Transitioning to APPROACH_BALL.");
                    currentState = STATE_APPROACH_BALL;
                }
                break;

            case STATE_APPROACH_BALL:
                // Transition: ball disappears (goes under the robot/intake)
                if (!ballVisible) {
                    log("Ball disappeared (likely under robot). Transitioning to CAPTURE_DRIVE.");
                    captureStartTime = now;
                    currentState = STATE_CAPTURE_DRIVE;
                    break;
                }

                // Action: Drive forward faster & Continue PID correction
                var approachRotation = calculatePIDRotation(ballX);
                smartDrive(0, APPROACH_FORWARD_SPEED, approachRotation * ROTATE_SPEED_MULTIPLIER); 

                // Optional fallback: If the ball drastically leaves the center, go back to tracking/slowing down
                if (Math.abs(ballX - CENTER_X) > (TARGET_CENTER_TOLERANCE * 2)) {
                    currentState = STATE_TRACK_BALL;
                }
                break;

            case STATE_CAPTURE_DRIVE:
                // Action: Drive forward blindly to capture
                smartDrive(0, CAPTURE_FORWARD_SPEED, 0); 
                
                // Transition: timer_done
                if (now - captureStartTime >= 2000) {
                    log("Capture timer done. Transitioning to SEARCH_BALL.");
                    currentState = STATE_SEARCH_BALL;
                }
                break;
        }

        // Loop delay acts as the base clock cycle
        // wait(35); 
        
    } catch (e) {
        console.log("Script interrupted or stopped: " + e);
        break;
    }
}