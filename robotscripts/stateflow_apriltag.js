// Configuration & Constants
var PING_PONG_LABEL = 32;
var CENTER_X = 0.5; // Assuming normalized coordinates (0.0 to 1.0)
var X_OFFSET = 0.1; // Adjust if the camera is not perfectly centered
var TARGET_CENTER_TOLERANCE = 0.1; 
var SIGHT_TIMEOUT_MS = 500; 

// --- NEW APRILTAG CONSTANTS ---
var N_SECONDS_TO_SWITCH = 15; // N seconds before interrupting to find AprilTag
var TARGET_APRILTAG_ID = 42;  // ID_VAL you are looking for
var TARGET_TAG_WIDTH = 0.5;   // Stop when AprilTag is half the screen width

// States
var STATE_SEARCH_BALL = 'SEARCH_BALL';
var STATE_TRACK_BALL = 'TRACK_BALL';
var STATE_APPROACH_BALL = 'APPROACH_BALL';
var STATE_CAPTURE_DRIVE = 'CAPTURE_DRIVE';

// --- NEW APRILTAG STATES ---
var STATE_SEARCH_APRILTAG = 'SEARCH_APRILTAG';
var STATE_APPROACH_APRILTAG = 'APPROACH_APRILTAG';
var STATE_DONE = 'DONE';

// Global Variables
var currentState = STATE_SEARCH_BALL;
var scriptStartTime = Date.now(); // Track when the script started
var lastSeenTime = 0;
var ballX = 0;
var ballY = 0;
var captureStartTime = 0;

// AprilTag Global Tracking Variables
var tagVisible = false;
var tagLastSeenTime = 0;
var tagX = 0;
var tagWidth = 0;

// PID Variables
var kp = 1.2;
var ki = 0.0;
var kd = 0.4;
var previousError = 0;
var integral = 0;

var ROTATE_SPEED_MULTIPLIER = 0.65; 

// Update tracking data when YOLO/AprilTag detects objects
onDetection(function(dets) {
    if (!dets) return;
    
    // --- YOLO PING PONG BALL DETECTION ---
    var yolodets = dets.get("yolo");
    if (yolodets) {
        var bestBall = null;
        var maxArea = 0;

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

        if (bestBall) {
            ballX = parseInt(bestBall.x) + X_OFFSET;
            ballY = bestBall.y;
            lastSeenTime = Date.now();
        }
    }

    // --- APRILTAG DETECTION ---
    var apriltags = dets.get("apriltags");
    if (apriltags) {
        var numTags = (typeof apriltags.size === 'function') ? apriltags.size() : apriltags.length;
        
        for (var j = 0; j < numTags; j++) {
            var tag = (typeof apriltags.get === 'function') ? apriltags.get(j) : apriltags[j];
            var id = (typeof tag.get === 'function') ? tag.get("id") : tag.id;

            if (Math.trunc(id) === TARGET_APRILTAG_ID) {
                tagVisible = true;
                tagLastSeenTime = Date.now();
                tagX = (typeof tag.get === 'function') ? tag.get("cx") : tag.cx;

                // Calculate width by finding the min and max X values from the corners
                var corners = (typeof tag.get === 'function') ? tag.get("corners") : tag.corners;
                if (corners) {
                    var minX = 1.0;
                    var maxX = 0.0;
                    var numCorners = (typeof corners.size === 'function') ? corners.size() : corners.length;
                    
                    for (var c = 0; c < numCorners; c++) {
                        var corner = (typeof corners.get === 'function') ? corners.get(c) : corners[c];
                        var cx = (typeof corner.get === 'function') ? corner.get(0) : corner[0];
                        if (cx < minX) minX = cx;
                        if (cx > maxX) maxX = cx;
                    }
                    tagWidth = maxX - minX;
                }
                break; // Target tag found, no need to process others
            }
        }
    }
});

// Helper function to calculate PID rotation
function calculatePIDRotation(targetX) {
    var error = targetX - CENTER_X; 
    integral += error;
    var derivative = error - previousError;
    previousError = error;
    
    var rotationSpeed = (kp * error) + (ki * integral) + (kd * derivative);
    return Math.max(-1.0, Math.min(1.0, rotationSpeed));
}

// Main State Machine Loop
while (true) {
    try {
        var now = Date.now();
        
        // --- TIMER OVERRIDE LOGIC ---
        // If N seconds have passed, forcefully switch to AprilTag mode
        var elapsedTimeSecs = (now - scriptStartTime) / 1000;
        if (elapsedTimeSecs >= N_SECONDS_TO_SWITCH && 
            currentState !== STATE_SEARCH_APRILTAG && 
            currentState !== STATE_APPROACH_APRILTAG && 
            currentState !== STATE_DONE) {
            
            log(N_SECONDS_TO_SWITCH + " seconds passed. Stopping current activity to find AprilTag " + TARGET_APRILTAG_ID);
            currentState = STATE_SEARCH_APRILTAG;
            integral = 0;       // Reset PID
            previousError = 0;
            drive(0,0,0);       // Stop existing movement
        }

        var timeSinceLastSeenBall = now - lastSeenTime;
        var ballVisible = (timeSinceLastSeenBall < SIGHT_TIMEOUT_MS);
        
        var timeSinceLastSeenTag = now - tagLastSeenTime;
        var currentTagVisible = tagVisible && (timeSinceLastSeenTag < SIGHT_TIMEOUT_MS);

        switch (currentState) {
            
            // ---------------------------------------------------------
            // ORIGINAL BALL TRACKING STATES
            // ---------------------------------------------------------
            case STATE_SEARCH_BALL:
                drive(0, 0, 0.3 * ROTATE_SPEED_MULTIPLIER); 
                if (ballVisible) {
                    log("Ball detected! Transitioning to TRACK_BALL.");
                    currentState = STATE_TRACK_BALL;
                    integral = 0; 
                    previousError = 0;
                }
                break;

            case STATE_TRACK_BALL:
                if (!ballVisible) {
                    currentState = STATE_SEARCH_BALL;
                    break;
                }
                var rotation = calculatePIDRotation(ballX);
                drive(0, 0, rotation * ROTATE_SPEED_MULTIPLIER); 
                
                if (Math.abs(ballX - CENTER_X) < TARGET_CENTER_TOLERANCE) {
                    currentState = STATE_APPROACH_BALL;
                }
                break;

            case STATE_APPROACH_BALL:
                if (!ballVisible) {
                    captureStartTime = now;
                    currentState = STATE_CAPTURE_DRIVE;
                    break;
                }
                var approachRotation = calculatePIDRotation(ballX);
                drive(0, 0.4, approachRotation * ROTATE_SPEED_MULTIPLIER); 
                
                if (Math.abs(ballX - CENTER_X) > (TARGET_CENTER_TOLERANCE * 2)) {
                    currentState = STATE_TRACK_BALL;
                }
                break;

            case STATE_CAPTURE_DRIVE:
                drive(0, 0.5, 0); 
                if (now - captureStartTime >= 2000) {
                    currentState = STATE_SEARCH_BALL;
                }
                break;

            // ---------------------------------------------------------
            // NEW APRILTAG STATES
            // ---------------------------------------------------------
            case STATE_SEARCH_APRILTAG:
                // Action: Rotate slowly scanning for the specific AprilTag
                drive(0, 0, 0.3 * ROTATE_SPEED_MULTIPLIER);
                
                // Transition: Target AprilTag is detected
                if (currentTagVisible) {
                    log("AprilTag " + TARGET_APRILTAG_ID + " detected! Transitioning to APPROACH_APRILTAG.");
                    currentState = STATE_APPROACH_APRILTAG;
                    integral = 0;
                    previousError = 0;
                }
                break;

            case STATE_APPROACH_APRILTAG:
                // Transition: Tag lost, go back to searching
                if (!currentTagVisible) {
                    log("AprilTag lost. Transitioning back to SEARCH_APRILTAG.");
                    currentState = STATE_SEARCH_APRILTAG;
                    break;
                }

                // Transition: Tag width reached 50% of the screen
                if (tagWidth >= TARGET_TAG_WIDTH) {
                    log("AprilTag reached target width (" + tagWidth.toFixed(2) + "). Stopping.");
                    drive(0, 0, 0);
                    currentState = STATE_DONE;
                    break;
                }

                // Action: PID steering using tagX to keep it perfectly centered while driving forward
                var tagRotation = calculatePIDRotation(tagX);
                drive(0, 0.3, tagRotation * ROTATE_SPEED_MULTIPLIER); // Drive forward 30% while tracking
                break;

            case STATE_DONE:
                // Mission complete. Keep the robot stopped.
                drive(0, 0, 0);
                break;
        }

        wait(50); 
        
    } catch (e) {
        log("Script interrupted or stopped: " + e);
        break;
    }
}