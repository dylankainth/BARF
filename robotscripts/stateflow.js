// http://10.104.125.78:8080/
// 10.104.125.167
// Configuration & Constants
var PING_PONG_LABEL = 32;
var CENTER_X = 0.5; // Assuming normalized YOLO coordinates (0.0 to 1.0)
var X_OFFSET = 0.1; // Adjust if the camera is not perfectly centered on the robot
var TARGET_CENTER_TOLERANCE = 0.1; // Margin of error to consider ball "centered"
var SIGHT_TIMEOUT_MS = 500; // How long before considering the ball "lost"

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


var ROTATE_SPEED_MULTIPLIER = 0.65; // Adjust this to make the robot faster or slower overall

// Update tracking data when YOLO detects objects
onDetection(function(dets) {
    if (!dets || dets.length === 0) return;
    
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
        ballX = parseInt(bestBall.x) + X_OFFSET; // Apply offset to center on robot
        ballY = bestBall.y;
        lastSeenTime = Date.now();
    }
});

// Helper function to calculate PID rotation
function calculatePIDRotation(currentX) {
    // If ball is at 0.8 (right), error is positive (0.3), turning robot right.
    var error = currentX - CENTER_X; 
    integral += error;
    var derivative = error - previousError;
    previousError = error;
    
    var rotationSpeed = (kp * error) + (ki * integral) + (kd * derivative);
    
    // Clamp the rotation speed between -1.0 and 1.0
    return Math.max(-1.0, Math.min(1.0, rotationSpeed));
}

// Main State Machine Loop
while (true) {
    try {
        var now = Date.now();
        var timeSinceLastSeen = now - lastSeenTime;
        var ballVisible = (timeSinceLastSeen < SIGHT_TIMEOUT_MS);

        switch (currentState) {
            
            case STATE_SEARCH_BALL:
                // Action: Rotate slowly scanning for ping pong balls
                drive(0, 0, 0.3 * ROTATE_SPEED_MULTIPLIER); // Sideways: 0, Forward: 0, Rotate: 30% Right
                
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

                // Action: PID steering using ball_x
                var rotation = calculatePIDRotation(ballX);
                drive(0, 0, rotation * ROTATE_SPEED_MULTIPLIER); // Rotate to center ball

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

                // Action: Drive forward & Continue PID correction
                var approachRotation = calculatePIDRotation(ballX);
                drive(0, 0.4, approachRotation * ROTATE_SPEED_MULTIPLIER); // Drive forward 40% while tracking

                // Optional fallback: If the ball drastically leaves the center, go back to tracking
                if (Math.abs(ballX - CENTER_X) > (TARGET_CENTER_TOLERANCE * 2)) {
                    currentState = STATE_TRACK_BALL;
                }
                break;

            case STATE_CAPTURE_DRIVE:
                // Action: Drive forward blindly to capture
                drive(0, 0.5, 0); 
                
                // Transition: timer_done
                if (now - captureStartTime >= 2000) {
                    log("Capture timer done. Transitioning to SEARCH_BALL.");
                    currentState = STATE_SEARCH_BALL;
                }
                break;
        }

        wait(50); // 50ms delay to run the loop at ~20Hz
        
    } catch (e) {
        log("Script interrupted or stopped: " + e);
        break;
    }
}