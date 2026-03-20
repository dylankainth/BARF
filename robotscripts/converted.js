// ==========================================
// CONFIGURATION
// ==========================================
var CONFIG = {
    VISION: {
        TARGET_LABEL: 32, // Ping pong ball
        CAMERA_OFFSET_MULTIPLIER: 1.6,
        SIGHT_TIMEOUT_MS: 250,
        AREA_SIMILARITY_RATIO: 0.8 // NEW: Balls within 80% of max area are considered "similar"
    },
    THRESHOLDS: {
        MIDDLE_X_FORWARD: 0.12,
        MIDDLE_X_STOP_ROTATE: 0.07
    },
    PID: {
        KP: 0.1,
        KI: 0.2,
        I_CLAMP: 2,
        OUTPUT_CLAMP: 0.6,
        MIN_OUTPUT: 0.1 
    },
    DRIVE: {
        FORWARD_SPEED: 0,//0.3,
        BLIND_FORWARD_SPEED: 0,//0.2,
        BLIND_FORWARD_DURATION_MS: 1700,
        SEARCH_SPIN_SPEED: 0,//0.2,
        PAUSE_DURATION_MS: 500, // 0.5 seconds pause to verify target
        RATE_LIMIT_MS: 40,
        EPSILON: 0.01
    }
};

// ==========================================
// RUNTIME STATE
// ==========================================
var STATE = {
    tracking: {
        mode: 'TRACKING', // 'TRACKING', 'BLIND_FORWARD', 'SPINNING', or 'VERIFYING'
        pauseStartTime: 0,
        ballXError: 0.0,
        lastSeenTime: 0,
        lastMoveForwardTime: 0
    },
    pid: {
        integral: 0.0,
        lastTime: Date.now()
    },
    hardware: {
        lastSentX: null,
        lastSentY: null,
        lastSentRot: null,
        lastCommandTime: 0
    }
};

// ==========================================
// UTILITY FUNCTIONS
// ==========================================
function clamp(value, min, max) {
    return value < min ? min : value > max ? max : value;
}

// ==========================================
// HARDWARE CONTROL
// ==========================================
function smartDrive(x, y, rot, currentTime) {
    var hw = STATE.hardware;
    if (currentTime - hw.lastCommandTime < CONFIG.DRIVE.RATE_LIMIT_MS) return; 

    var xChanged   = hw.lastSentX === null   || Math.abs(x - hw.lastSentX) > CONFIG.DRIVE.EPSILON;
    var yChanged   = hw.lastSentY === null   || Math.abs(y - hw.lastSentY) > CONFIG.DRIVE.EPSILON;
    var rotChanged = hw.lastSentRot === null || Math.abs(rot - hw.lastSentRot) > CONFIG.DRIVE.EPSILON;

    if (xChanged || yChanged || rotChanged) {
        // log(STATE.tracking.mode);
        log("x: "+ STATE.tracking.ballXError);
        log("I:"+STATE.pid.integral);
        drive(x, y, rot); 
        hw.lastSentX = x;
        hw.lastSentY = y;
        hw.lastSentRot = rot;
        hw.lastCommandTime = currentTime;
    }
}

// ==========================================
// VISION PROCESSING
// ==========================================
function getLargestTarget(yoloDetections) {
    var numDetections = Math.trunc(yoloDetections.size());
    var validBalls = [];
    var maxArea = 0;

    // Pass 1: Collect all valid balls and find the maximum area
    for (var i = 0; i < numDetections; i++) {
        var det = yoloDetections.get(i);
        if (Math.trunc(det.get("label")) === CONFIG.VISION.TARGET_LABEL) {
            var width = parseFloat(det.get("w"));
            var area = width * det.get("h");
            var x = parseFloat(det.get("x")) + (width * 0.5) + (width * CONFIG.VISION.CAMERA_OFFSET_MULTIPLIER);
            // var x = parseFloat(det.get("x"));
            
            validBalls.push({ x: x, area: area });
            if (area > maxArea) {
                maxArea = area;
            }
        }
    }

    if (validBalls.length === 0) return null;

    // Pass 2: Find the most centered ball among those with a similar area
    var bestBall = null;
    var minCenterDist = Infinity;
    var areaThreshold = maxArea * CONFIG.VISION.AREA_SIMILARITY_RATIO;

    for (var j = 0; j < validBalls.length; j++) {
        var ball = validBalls[j];
        
        // Only consider balls that are at least 'AREA_SIMILARITY_RATIO' the size of the biggest one
        if (ball.area >= areaThreshold) {
            // Calculate distance from the center (0.5)
            var centerDist = Math.abs(ball.x - 0.5);
            
            if (centerDist < minCenterDist) {
                minCenterDist = centerDist;
                bestBall = ball;
            }
        }
    }

    return bestBall;
}

onDetection(function(dets) {
    if (!dets || !dets.get("yolo")) return;
    var target = getLargestTarget(dets.get("yolo"));
    if (target) {
        STATE.tracking.ballXError = (target.x - 0.5) * 2.0;
        STATE.tracking.lastSeenTime = Date.now();
    }
});

function calculateRotation(dt, isTargetValid) {
    if (!isTargetValid || Math.abs(STATE.tracking.ballXError) < CONFIG.THRESHOLDS.MIDDLE_X_STOP_ROTATE) {
        STATE.pid.integral = 0.0;
        return 0.0;
    }
    if (dt > 0) {
        STATE.pid.integral += STATE.tracking.ballXError * dt;
        STATE.pid.integral = clamp(STATE.pid.integral, -CONFIG.PID.I_CLAMP, CONFIG.PID.I_CLAMP);
    }
    var rawRotation = (STATE.tracking.ballXError * CONFIG.PID.KP) + (STATE.pid.integral * CONFIG.PID.KI);
    return clamp(rawRotation, -CONFIG.PID.OUTPUT_CLAMP, CONFIG.PID.OUTPUT_CLAMP);
}

// ==========================================
// MOVEMENT LOGIC (CONTROLLERS)
// ==========================================
function determineVelocities(currentTime, dt) {
    var isTargetValid = (currentTime - STATE.tracking.lastSeenTime) < CONFIG.VISION.SIGHT_TIMEOUT_MS;
    
    // 1. Handle "Pause and Verify" state
    if (STATE.tracking.mode === 'VERIFYING') {
        if (currentTime - STATE.tracking.pauseStartTime < CONFIG.DRIVE.PAUSE_DURATION_MS) {
            // Still pausing: Freeze all motors
            return { x: 0.0, y: 0.0, rot: 0.0 };
        } else {
            // Pause is over: Did the target stick around?
            if (!isTargetValid) {
                // False alarm! Resume spinning immediately. 
                STATE.tracking.mode = 'SPINNING';
                // Artificially push back the blind forward timer so we don't accidentally drive blind again
                STATE.tracking.lastMoveForwardTime = currentTime - CONFIG.DRIVE.BLIND_FORWARD_DURATION_MS;
                return { x: 0.0, y: 0.0, rot: CONFIG.DRIVE.SEARCH_SPIN_SPEED };
            }
            // If valid, it falls through to TRACKING below.
            STATE.tracking.mode = 'TRACKING';
        }
    }

    // 2. Trigger "Pause and Verify" if we just found something while spinning
    if (STATE.tracking.mode === 'SPINNING' && isTargetValid) {
        STATE.tracking.mode = 'VERIFYING';
        STATE.tracking.pauseStartTime = currentTime;
        STATE.pid.integral = 0.0; // Reset PID for a clean slate
        return { x: 0.0, y: 0.0, rot: 0.0 }; // Stop motors immediately
    }

    // 3. Normal Movement Logic
    var rotation = 0.0;
    var forwardSpeed = 0.0;

    if (isTargetValid) {
        STATE.tracking.mode = 'TRACKING';
        rotation = calculateRotation(dt, true);

        if (Math.abs(STATE.tracking.ballXError) < CONFIG.THRESHOLDS.MIDDLE_X_FORWARD) {
            forwardSpeed = CONFIG.DRIVE.FORWARD_SPEED;
        }
        STATE.tracking.lastMoveForwardTime = currentTime;

    } else {
        var blindElapsed = currentTime - STATE.tracking.lastMoveForwardTime;
        
        if (blindElapsed < CONFIG.DRIVE.BLIND_FORWARD_DURATION_MS) {
            STATE.tracking.mode = 'BLIND_FORWARD';
            forwardSpeed = CONFIG.DRIVE.BLIND_FORWARD_SPEED;
        } else {
            STATE.tracking.mode = 'SPINNING';
            rotation = CONFIG.DRIVE.SEARCH_SPIN_SPEED;
        }
    }

    if (forwardSpeed === 0.0 && rotation !== 0.0 && Math.abs(rotation) < CONFIG.PID.MIN_OUTPUT) {
        rotation = rotation > 0 ? CONFIG.PID.MIN_OUTPUT : -CONFIG.PID.MIN_OUTPUT;
    }

    return { x: 0.0, y: forwardSpeed, rot: rotation };
}

// ==========================================
// MAIN CONTROL LOOP
// ==========================================
stopAprilTag();
startYolo();

while (true) {
    try {
        var currentTime = Date.now();
        var dt = (currentTime - STATE.pid.lastTime) / 1000.0;
        STATE.pid.lastTime = currentTime;

        var cmd = determineVelocities(currentTime, dt);
        smartDrive(cmd.x, cmd.y, cmd.rot, currentTime);
        
    } catch (e) {
        console.log("Script interrupted or stopped: " + e);
        break;
    }
}