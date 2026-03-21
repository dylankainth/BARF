// ==========================================
// CONFIGURATION
// ==========================================
var CONFIG = {
    VISION: {
        TARGET_LABEL: 32, // Ping pong ball
        CAMERA_OFFSET_MULTIPLIER: 1.1,
        SIGHT_TIMEOUT_MS: 120,
        AREA_SIMILARITY_RATIO: 0.8
    },
    THRESHOLDS: {
        MIDDLE_X_FORWARD: 0.5,
        BLIND_FORWARD_MIDDLE_X: 0.2
    },
    PID: {
        KP: 0.1,
        KI: 0.1,
        I_CLAMP: 1.4,
        OUTPUT_CLAMP: 0.6,
        MIN_OUTPUT: 0.15
    },
    DRIVE: {
        FORWARD_SPEED_MIN: 0.25,
        FORWARD_SPEED_MAX: 0.40,
        BLIND_FORWARD_SPEED: 0.6,
        BLIND_FORWARD_DURATION_MS: 1700,
        SEARCH_SPIN_SPEED: 0.3,
        PAUSE_DURATION_MS: 250,
        RATE_LIMIT_MS: 40,
        EPSILON: 0.01,

        // --- NEW: Search Step-and-Stop Timings ---
        STEP_TIME_MS: 150,
        PAUSE_TIME_MS: 300
    }
};

// ==========================================
// RUNTIME STATE
// ==========================================
var STATE = {
    tracking: {
        mode: 'TRACKING',
        pauseStartTime: 0,
        ballXError: 0.0,
        lastSeenTime: 0,
        lastMoveForwardTime: 0
    },
    pid: {
        integral: 0.0,
        lastTime: Date.now(),
        lastError: 0.0
    },
    hardware: {
        lastSentX: null,
        lastSentY: null,
        lastSentRot: null,
        lastCommandTime: 0
    },
    // --- NEW: Search Phase State ---
    searchPhase: {
        phase: 'PAUSED',
        phaseStartTime: 0
    }
};

// Script timing
var scriptStartTime = Date.now();
const TRACKING_DURATION_MS = 120000;    // 2 minutes of normal tracking
const SCRIPT_DURATION_MS = 150000;      // 2.5 minutes total (120 + 30)

// ==========================================
// UTILITY FUNCTIONS
// ==========================================
function clamp(value, min, max) {
    return value < min ? min : value > max ? max : value;
}

// ==========================================
// PARKING FUNCTION
// ==========================================
function park() {
    // Empty parking function
}

// ==========================================
// HARDWARE CONTROL
// ==========================================
function smartDrive(x, y, rot, currentTime) {
    var hw = STATE.hardware;
    if (currentTime - hw.lastCommandTime < CONFIG.DRIVE.RATE_LIMIT_MS) return;

    var xChanged = hw.lastSentX === null || Math.abs(x - hw.lastSentX) > CONFIG.DRIVE.EPSILON;
    var yChanged = hw.lastSentY === null || Math.abs(y - hw.lastSentY) > CONFIG.DRIVE.EPSILON;
    var rotChanged = hw.lastSentRot === null || Math.abs(rot - hw.lastSentRot) > CONFIG.DRIVE.EPSILON;

    if (xChanged || yChanged || rotChanged) {
        log("mode: " + STATE.tracking.mode);
        log("x: " + STATE.tracking.ballXError);
        log("I:" + STATE.pid.integral);
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

    for (var i = 0; i < numDetections; i++) {
        var det = yoloDetections.get(i);
        if (Math.trunc(det.get("label")) === CONFIG.VISION.TARGET_LABEL) {
            var width = parseFloat(det.get("w"));
            var area = width * det.get("h");
            var x = parseFloat(det.get("x")) + (width * 0.5) + (width * CONFIG.VISION.CAMERA_OFFSET_MULTIPLIER);

            validBalls.push({ x: x, area: area });
            if (area > maxArea) {
                maxArea = area;
            }
        }
    }

    if (validBalls.length === 0) return null;

    var bestBall = null;
    var minCenterDist = Infinity;
    var areaThreshold = maxArea * CONFIG.VISION.AREA_SIMILARITY_RATIO;

    for (var j = 0; j < validBalls.length; j++) {
        var ball = validBalls[j];

        if (ball.area >= areaThreshold) {
            var centerDist = Math.abs(ball.x - 0.5);
            if (centerDist < minCenterDist) {
                minCenterDist = centerDist;
                bestBall = ball;
            }
        }
    }

    return bestBall;
}

onDetection(function (dets) {
    if (!dets || !dets.get("yolo")) return;
    var target = getLargestTarget(dets.get("yolo"));
    if (target) {
        STATE.tracking.ballXError = (target.x - 0.5) * 2.0;
        STATE.tracking.lastSeenTime = Date.now();
    }
});

function calculateRotation(dt, isTargetValid) {
    if (!isTargetValid) {
        STATE.pid.integral = 0.0;
        STATE.pid.lastError = 0.0;
        return 0.0;
    }

    if (STATE.tracking.ballXError * STATE.pid.lastError < 0) {
        STATE.pid.integral = 0.0;
    }

    if (dt > 0) {
        STATE.pid.integral += STATE.tracking.ballXError * dt;
        STATE.pid.integral = clamp(STATE.pid.integral, -CONFIG.PID.I_CLAMP, CONFIG.PID.I_CLAMP);
    }

    var rawRotation = (STATE.tracking.ballXError * CONFIG.PID.KP) + (STATE.pid.integral * CONFIG.PID.KI);
    STATE.pid.lastError = STATE.tracking.ballXError;

    return clamp(rawRotation, -CONFIG.PID.OUTPUT_CLAMP, CONFIG.PID.OUTPUT_CLAMP);
}

// ==========================================
// MOVEMENT LOGIC (CONTROLLERS)
// ==========================================
function determineVelocities(currentTime, dt) {
    var isTargetValid = (currentTime - STATE.tracking.lastSeenTime) < CONFIG.VISION.SIGHT_TIMEOUT_MS;

    if (STATE.tracking.mode === 'VERIFYING') {
        if (currentTime - STATE.tracking.pauseStartTime < CONFIG.DRIVE.PAUSE_DURATION_MS) {
            return { x: 0.0, y: 0.0, rot: 0.0 };
        } else {
            if (!isTargetValid) {
                STATE.tracking.mode = 'SPINNING';
                STATE.tracking.lastMoveForwardTime = currentTime - CONFIG.DRIVE.BLIND_FORWARD_DURATION_MS;
                // Initialize the search phase when entering SPINNING mode
                STATE.searchPhase.phase = 'PAUSED';
                STATE.searchPhase.phaseStartTime = currentTime;
                return { x: 0.0, y: 0.0, rot: 0.0 };
            }
            STATE.tracking.mode = 'TRACKING';
        }
    }

    if (STATE.tracking.mode === 'SPINNING' && isTargetValid) {
        STATE.tracking.mode = 'VERIFYING';
        STATE.tracking.pauseStartTime = currentTime;
        STATE.pid.integral = 0.0;
        return { x: 0.0, y: 0.0, rot: 0.0 };
    }

    var rotation = 0.0;
    var forwardSpeed = 0.0;

    if (isTargetValid) {
        STATE.tracking.mode = 'TRACKING';
        rotation = calculateRotation(dt, true);

        var absXError = Math.abs(STATE.tracking.ballXError);

        if (absXError < CONFIG.THRESHOLDS.MIDDLE_X_FORWARD) {
            // Calculate how centered the ball is (1.0 = perfect center, 0.0 = edge of threshold)
            var centeringAccuracy = 1.0 - (absXError / CONFIG.THRESHOLDS.MIDDLE_X_FORWARD);

            // Map the accuracy smoothly between min and max speed
            forwardSpeed = CONFIG.DRIVE.FORWARD_SPEED_MIN +
                (centeringAccuracy * (CONFIG.DRIVE.FORWARD_SPEED_MAX - CONFIG.DRIVE.FORWARD_SPEED_MIN));

            STATE.tracking.lastMoveForwardTime = currentTime;
        }

    } else {
        var blindElapsed = currentTime - STATE.tracking.lastMoveForwardTime;

        if (blindElapsed < CONFIG.DRIVE.BLIND_FORWARD_DURATION_MS &&
            Math.abs(STATE.tracking.ballXError) < CONFIG.THRESHOLDS.BLIND_FORWARD_MIDDLE_X) {
            STATE.tracking.mode = 'BLIND_FORWARD';
            forwardSpeed = CONFIG.DRIVE.BLIND_FORWARD_SPEED;
        } else {
            // --- NEW: Step-and-Stop Spinning Logic ---
            if (STATE.tracking.mode !== 'SPINNING') {
                // Failsafe initialization if entering from tracking directly
                STATE.tracking.mode = 'SPINNING';
                STATE.searchPhase.phase = 'PAUSED';
                STATE.searchPhase.phaseStartTime = currentTime;
            }

            var timeInPhase = currentTime - STATE.searchPhase.phaseStartTime;

            if (STATE.searchPhase.phase === 'PAUSED') {
                if (timeInPhase > CONFIG.DRIVE.PAUSE_TIME_MS) {
                    STATE.searchPhase.phase = 'MOVING';
                    STATE.searchPhase.phaseStartTime = currentTime;
                }
                rotation = 0.0; // Stop and look
            } else {
                if (timeInPhase > CONFIG.DRIVE.STEP_TIME_MS) {
                    STATE.searchPhase.phase = 'PAUSED';
                    STATE.searchPhase.phaseStartTime = currentTime;
                    rotation = 0.0; // Ready to pause again
                } else {
                    rotation = CONFIG.DRIVE.SEARCH_SPIN_SPEED; // Spin
                }
            }
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
playAudio('fardinho');

while (true) {
    try {
        var currentTime = Date.now();
        var elapsedMs = currentTime - scriptStartTime;

        if (elapsedMs > SCRIPT_DURATION_MS) {
            // Total time exceeded, stop
            stop();
            break;
        } else if (elapsedMs > TRACKING_DURATION_MS) {
            // In parking mode (last 30 seconds)
            park();
        } else {
            // Normal tracking mode
            var dt = (currentTime - STATE.pid.lastTime) / 1000.0;
            STATE.pid.lastTime = currentTime;

            var cmd = determineVelocities(currentTime, dt);
            smartDrive(cmd.x, cmd.y, cmd.rot, currentTime);
        }
    } catch (e) {
        console.log("Script interrupted or stopped: " + e);
        break;
    }
}