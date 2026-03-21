// ==========================================
// COMBINED TRACKING + PARKING SCRIPT
// Runs ball tracking for 2 min, then AprilTag parking sequence
// ==========================================

// ==========================================
// PHASE CONFIGURATION
// ==========================================
const PHASE = {
    BALL_TRACKING: 'BALL_TRACKING',
    PARKING: 'PARKING'
};

var currentPhase = PHASE.BALL_TRACKING;

// Script timing
var scriptStartTime = Date.now();
const TRACKING_DURATION_MS = 120000;    // 2 minutes of ball tracking
const SCRIPT_DURATION_MS = 150000;      // 2.5 minutes total

// ==========================================
// BALL TRACKING CONFIG (convertedtimer)
// ==========================================
var BALL_CONFIG = {
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
        FORWARD_SPEED_MIN: 0.3,
        FORWARD_SPEED_MAX: 0.60,
        BLIND_FORWARD_SPEED: 0.6,
        BLIND_FORWARD_DURATION_MS: 1700,
        SEARCH_SPIN_SPEED: 0.3,
        PAUSE_DURATION_MS: 250,
        RATE_LIMIT_MS: 40,
        EPSILON: 0.01,
        STEP_TIME_MS: 150,
        PAUSE_TIME_MS: 300
    }
};

// ==========================================
// PARKING CONFIG (testalignment3)
// ==========================================
var PARKING_CONFIG = {
    VISION: {
        TARGET_APRILTAG_IDs: [20, 21],
        CAMERA_OFFSET_MULTIPLIER: 1.1,
        SIGHT_TIMEOUT_MS: 3000
    },
    THRESHOLDS: {
        MIDDLE_X_FORWARD: 0.5
    },
    PID: {
        KP: 0.1,
        KI: 0.1,
        I_CLAMP: 1.4,
        OUTPUT_CLAMP: 0.6,
        MIN_OUTPUT: 0.15
    },
    DRIVE: {
        APPROACH_SPEED_MIN: 0.25,
        APPROACH_SPEED_MAX: 0.5,
        SEARCH_SPIN_SPEED: 0.4,
        RATE_LIMIT_MS: 40,
        EPSILON: 0.01,
        STEP_TIME_MS: 100,
        PAUSE_TIME_MS: 300,
        INITIAL_PAUSE_MS: 1500,
        REVERSE_SPEED: -0.3,
        REVERSE_STEP_MS: 400,
        REVERSE_PAUSE_MS: 600,
        MAX_REVERSE_TIME_MS: 6000,
        TURN_180_SPEED: 0.4,
        TURN_180_TOLERANCE: 0.15,
        PARKING_TIME_MS: 4000
    }
};

// ==========================================
// UNIFIED STATE
// ==========================================
var STATE = {
    // Ball tracking state
    ballTracking: {
        mode: 'TRACKING',
        pauseStartTime: 0,
        ballXError: 0.0,
        lastSeenTime: 0,
        lastMoveForwardTime: 0,
        searchPhase: {
            phase: 'PAUSED',
            phaseStartTime: 0
        }
    },
    // Parking state
    parking: {
        mode: 'SEARCHING',
        tracking: {
            lastSeenTime: 0,
            firstSeenTime: 0,
            tagXError: 0.0
        },
        searchPhase: {
            phase: 'PAUSED',
            phaseStartTime: 0
        },
        reversePhase: {
            phase: 'PAUSED',
            phaseStartTime: 0,
            totalStartTime: 0
        },
        turnPhase: {
            targetYaw: 0
        },
        reverseAgainPhase: {
            phase: 'PAUSED',
            phaseStartTime: 0,
            totalStartTime: 0
        }
    },
    // Shared
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
    }
};

// ==========================================
// UTILITY FUNCTIONS
// ==========================================
function clamp(value, min, max) {
    return value < min ? min : value > max ? max : value;
}

function angleDiff(a, b) {
    var diff = a - b;
    while (diff > Math.PI) diff -= 2 * Math.PI;
    while (diff < -Math.PI) diff += 2 * Math.PI;
    return diff;
}

function getYaw() {
    var raw = getQuaternion();
    if (!raw || raw.length < 4) return 0.0;
    var q = {
        w: raw[0],
        x: raw[1],
        y: raw[2],
        z: raw[3]
    };
    return Math.atan2(2.0 * (q.w * q.z + q.x * q.y), 1.0 - 2.0 * (q.y * q.y + q.z * q.z));
}

// ==========================================
// HARDWARE CONTROL
// ==========================================
function smartDrive(x, y, rot, currentTime) {
    var hw = STATE.hardware;
    if (currentTime - hw.lastCommandTime < 40) return; // Both configs use 40ms rate limit

    var xChanged = hw.lastSentX === null || Math.abs(x - hw.lastSentX) > 0.01;
    var yChanged = hw.lastSentY === null || Math.abs(y - hw.lastSentY) > 0.01;
    var rotChanged = hw.lastSentRot === null || Math.abs(rot - hw.lastSentRot) > 0.01;

    if (xChanged || yChanged || rotChanged) {
        drive(x, y, rot);
        hw.lastSentX = x;
        hw.lastSentY = y;
        hw.lastSentRot = rot;
        hw.lastCommandTime = currentTime;
    }
}

// ==========================================
// BALL TRACKING VISION
// ==========================================
function getLargestBall(yoloDetections) {
    var numDetections = Math.trunc(yoloDetections.size());
    var validBalls = [];
    var maxArea = 0;

    for (var i = 0; i < numDetections; i++) {
        var det = yoloDetections.get(i);
        if (Math.trunc(det.get("label")) === BALL_CONFIG.VISION.TARGET_LABEL) {
            var width = parseFloat(det.get("w"));
            var area = width * det.get("h");
            var x = parseFloat(det.get("x")) + (width * 0.5) + (width * BALL_CONFIG.VISION.CAMERA_OFFSET_MULTIPLIER);
            validBalls.push({ x: x, area: area });
            if (area > maxArea) maxArea = area;
        }
    }

    if (validBalls.length === 0) return null;

    var bestBall = null;
    var minCenterDist = Infinity;
    var areaThreshold = maxArea * BALL_CONFIG.VISION.AREA_SIMILARITY_RATIO;

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

// ==========================================
// PARKING VISION
// ==========================================
function getAprilTag(detections) {
    if (!detections) return null;
    var apriltags = detections.get("apriltags");
    if (!apriltags) return null;

    for (var j = 0; j < apriltags.size(); j++) {
        var tag = apriltags.get(j);
        var id = Math.trunc(tag.get("id"));
        if (id === PARKING_CONFIG.VISION.TARGET_APRILTAG_IDs[0] ||
            id === PARKING_CONFIG.VISION.TARGET_APRILTAG_IDs[1]) {
            return tag;
        }
    }
    return null;
}

// ==========================================
// GLOBAL DETECTION HANDLER
// ==========================================
onDetection(function (dets) {
    if (!dets) return;

    if (currentPhase === PHASE.BALL_TRACKING) {
        if (dets.get("yolo")) {
            var target = getLargestBall(dets.get("yolo"));
            if (target) {
                STATE.ballTracking.ballXError = (target.x - 0.5) * 2.0;
                STATE.ballTracking.lastSeenTime = Date.now();
            }
        }
    } else if (currentPhase === PHASE.PARKING) {
        var tag = getAprilTag(dets);
        if (tag) {
            STATE.parking.tracking.lastSeenTime = Date.now();
            var cx = parseFloat(tag.get("cx"));
            var w = parseFloat(tag.get("w"));
            if (!isNaN(cx)) {
                var widthOffset = !isNaN(w) ? (w * PARKING_CONFIG.VISION.CAMERA_OFFSET_MULTIPLIER) : 0.0;
                var adjustedCx = cx + widthOffset;
                STATE.parking.tracking.tagXError = (adjustedCx - 0.5) * 2.0;
            }
        }
    }
});

// ==========================================
// BALL TRACKING PID & MOVEMENT
// ==========================================
function calculateBallRotation(dt, isTargetValid) {
    if (!isTargetValid) {
        STATE.pid.integral = 0.0;
        STATE.pid.lastError = 0.0;
        return 0.0;
    }

    if (STATE.ballTracking.ballXError * STATE.pid.lastError < 0) {
        STATE.pid.integral = 0.0;
    }

    if (dt > 0) {
        STATE.pid.integral += STATE.ballTracking.ballXError * dt;
        STATE.pid.integral = clamp(STATE.pid.integral, -BALL_CONFIG.PID.I_CLAMP, BALL_CONFIG.PID.I_CLAMP);
    }

    var rawRotation = (STATE.ballTracking.ballXError * BALL_CONFIG.PID.KP) + (STATE.pid.integral * BALL_CONFIG.PID.KI);
    STATE.pid.lastError = STATE.ballTracking.ballXError;

    return clamp(rawRotation, -BALL_CONFIG.PID.OUTPUT_CLAMP, BALL_CONFIG.PID.OUTPUT_CLAMP);
}

function determineBallVelocities(currentTime, dt) {
    var isTargetValid = (currentTime - STATE.ballTracking.lastSeenTime) < BALL_CONFIG.VISION.SIGHT_TIMEOUT_MS;

    if (STATE.ballTracking.mode === 'VERIFYING') {
        if (currentTime - STATE.ballTracking.pauseStartTime < BALL_CONFIG.DRIVE.PAUSE_DURATION_MS) {
            return { x: 0.0, y: 0.0, rot: 0.0 };
        } else {
            if (!isTargetValid) {
                STATE.ballTracking.mode = 'SPINNING';
                STATE.ballTracking.lastMoveForwardTime = currentTime - BALL_CONFIG.DRIVE.BLIND_FORWARD_DURATION_MS;
                STATE.ballTracking.searchPhase.phase = 'PAUSED';
                STATE.ballTracking.searchPhase.phaseStartTime = currentTime;
                return { x: 0.0, y: 0.0, rot: 0.0 };
            }
            STATE.ballTracking.mode = 'TRACKING';
        }
    }

    if (STATE.ballTracking.mode === 'SPINNING' && isTargetValid) {
        STATE.ballTracking.mode = 'VERIFYING';
        STATE.ballTracking.pauseStartTime = currentTime;
        STATE.pid.integral = 0.0;
        return { x: 0.0, y: 0.0, rot: 0.0 };
    }

    var rotation = 0.0;
    var forwardSpeed = 0.0;

    if (isTargetValid) {
        STATE.ballTracking.mode = 'TRACKING';
        rotation = calculateBallRotation(dt, true);
        var absXError = Math.abs(STATE.ballTracking.ballXError);

        if (absXError < BALL_CONFIG.THRESHOLDS.MIDDLE_X_FORWARD) {
            var centeringAccuracy = 1.0 - (absXError / BALL_CONFIG.THRESHOLDS.MIDDLE_X_FORWARD);
            forwardSpeed = BALL_CONFIG.DRIVE.FORWARD_SPEED_MIN +
                (centeringAccuracy * (BALL_CONFIG.DRIVE.FORWARD_SPEED_MAX - BALL_CONFIG.DRIVE.FORWARD_SPEED_MIN));
            STATE.ballTracking.lastMoveForwardTime = currentTime;
        }
    } else {
        var blindElapsed = currentTime - STATE.ballTracking.lastMoveForwardTime;
        if (blindElapsed < BALL_CONFIG.DRIVE.BLIND_FORWARD_DURATION_MS &&
            Math.abs(STATE.ballTracking.ballXError) < BALL_CONFIG.THRESHOLDS.BLIND_FORWARD_MIDDLE_X) {
            STATE.ballTracking.mode = 'BLIND_FORWARD';
            forwardSpeed = BALL_CONFIG.DRIVE.BLIND_FORWARD_SPEED;
        } else {
            if (STATE.ballTracking.mode !== 'SPINNING') {
                STATE.ballTracking.mode = 'SPINNING';
                STATE.ballTracking.searchPhase.phase = 'PAUSED';
                STATE.ballTracking.searchPhase.phaseStartTime = currentTime;
            }

            var timeInPhase = currentTime - STATE.ballTracking.searchPhase.phaseStartTime;
            if (STATE.ballTracking.searchPhase.phase === 'PAUSED') {
                if (timeInPhase > BALL_CONFIG.DRIVE.PAUSE_TIME_MS) {
                    STATE.ballTracking.searchPhase.phase = 'MOVING';
                    STATE.ballTracking.searchPhase.phaseStartTime = currentTime;
                }
                rotation = 0.0;
            } else {
                if (timeInPhase > BALL_CONFIG.DRIVE.STEP_TIME_MS) {
                    STATE.ballTracking.searchPhase.phase = 'PAUSED';
                    STATE.ballTracking.searchPhase.phaseStartTime = currentTime;
                    rotation = 0.0;
                } else {
                    rotation = BALL_CONFIG.DRIVE.SEARCH_SPIN_SPEED;
                }
            }
        }
    }

    if (forwardSpeed === 0.0 && rotation !== 0.0 && Math.abs(rotation) < BALL_CONFIG.PID.MIN_OUTPUT) {
        rotation = rotation > 0 ? BALL_CONFIG.PID.MIN_OUTPUT : -BALL_CONFIG.PID.MIN_OUTPUT;
    }

    return { x: 0.0, y: forwardSpeed, rot: rotation };
}

// ==========================================
// PARKING PID & MOVEMENT
// ==========================================
function calculateParkingRotation(dt, isTargetValid) {
    if (!isTargetValid) {
        STATE.pid.integral = 0.0;
        STATE.pid.lastError = 0.0;
        return 0.0;
    }

    if (STATE.parking.tracking.tagXError * STATE.pid.lastError < 0) {
        STATE.pid.integral = 0.0;
    }

    if (dt > 0) {
        STATE.pid.integral += STATE.parking.tracking.tagXError * dt;
        STATE.pid.integral = clamp(STATE.pid.integral, -PARKING_CONFIG.PID.I_CLAMP, PARKING_CONFIG.PID.I_CLAMP);
    }

    var rawRotation = (STATE.parking.tracking.tagXError * PARKING_CONFIG.PID.KP) + (STATE.pid.integral * PARKING_CONFIG.PID.KI);
    STATE.pid.lastError = STATE.parking.tracking.tagXError;

    return clamp(rawRotation, -PARKING_CONFIG.PID.OUTPUT_CLAMP, PARKING_CONFIG.PID.OUTPUT_CLAMP);
}

function determineParkingVelocities(currentTime, dt) {
    var timeSinceSeen = currentTime - STATE.parking.tracking.lastSeenTime;
    var isVisible = timeSinceSeen < PARKING_CONFIG.VISION.SIGHT_TIMEOUT_MS;

    // STATE TRANSITIONS
    if (STATE.parking.mode === 'TURNING_180') {
        var currentYaw = getYaw();
        var diff = Math.abs(angleDiff(currentYaw, STATE.parking.turnPhase.targetYaw));
        if (diff < PARKING_CONFIG.DRIVE.TURN_180_TOLERANCE) {
            STATE.parking.mode = 'REVERSING_AGAIN';
            STATE.parking.reverseAgainPhase.totalStartTime = currentTime;
            log("180 turn complete. Starting final parking reverse...");
        }
    }
    else if (STATE.parking.mode === 'REVERSING_AGAIN') {
        if (currentTime - STATE.parking.reverseAgainPhase.totalStartTime > PARKING_CONFIG.DRIVE.PARKING_TIME_MS) {
            STATE.parking.mode = 'STOPPED';
            log("Parking reverse complete. Robot successfully PARKED.");
        }
    }
    else if (!isVisible) {
        if (STATE.parking.mode === 'APPROACHING') {
            STATE.parking.mode = 'REVERSING';
            STATE.parking.reversePhase.totalStartTime = currentTime;
            STATE.parking.reversePhase.phase = 'PAUSED';
            STATE.parking.reversePhase.phaseStartTime = currentTime;
            log("Lost tag during approach. Reversing to reacquire...");
        }
        else if (STATE.parking.mode === 'REVERSING') {
            if (currentTime - STATE.parking.reversePhase.totalStartTime > PARKING_CONFIG.DRIVE.MAX_REVERSE_TIME_MS) {
                STATE.parking.mode = 'SEARCHING';
                STATE.parking.searchPhase.phase = 'PAUSED';
                STATE.parking.searchPhase.phaseStartTime = currentTime;
                log("Reverse timeout reached without spotting tag. Resuming search...");
            }
        }
        else if (STATE.parking.mode !== 'SEARCHING' && STATE.parking.mode !== 'STOPPED') {
            STATE.parking.mode = 'SEARCHING';
            STATE.parking.searchPhase.phase = 'PAUSED';
            STATE.parking.searchPhase.phaseStartTime = currentTime;
            log("Tag lost. Searching...");
        }
    }
    else {
        if (STATE.parking.mode === 'SEARCHING') {
            STATE.parking.mode = 'INITIAL_PAUSE';
            STATE.parking.tracking.firstSeenTime = currentTime;
            STATE.pid.integral = 0.0;
            log("Tag found! Pausing...");
        }
        else if (STATE.parking.mode === 'REVERSING') {
            STATE.parking.mode = 'TURNING_180';
            var currentYaw = getYaw();
            STATE.parking.turnPhase.targetYaw = currentYaw + Math.PI;
            while (STATE.parking.turnPhase.targetYaw > Math.PI) STATE.parking.turnPhase.targetYaw -= 2 * Math.PI;
            while (STATE.parking.turnPhase.targetYaw < -Math.PI) STATE.parking.turnPhase.targetYaw += 2 * Math.PI;
            log("Tag spotted during reverse! Commencing 180 turn and park sequence...");
        }
        else if (STATE.parking.mode === 'INITIAL_PAUSE' && (currentTime - STATE.parking.tracking.firstSeenTime > PARKING_CONFIG.DRIVE.INITIAL_PAUSE_MS)) {
            STATE.parking.mode = 'APPROACHING';
            log("Approaching tag...");
        }
    }

    // MOVEMENT COMMANDS
    if (STATE.parking.mode === 'SEARCHING') {
        var timeInPhase = currentTime - STATE.parking.searchPhase.phaseStartTime;
        if (STATE.parking.searchPhase.phase === 'PAUSED') {
            if (timeInPhase > PARKING_CONFIG.DRIVE.PAUSE_TIME_MS) {
                STATE.parking.searchPhase.phase = 'MOVING';
                STATE.parking.searchPhase.phaseStartTime = currentTime;
            }
            return { x: 0.0, y: 0.0, rot: 0.0 };
        } else {
            if (timeInPhase > PARKING_CONFIG.DRIVE.STEP_TIME_MS) {
                STATE.parking.searchPhase.phase = 'PAUSED';
                STATE.parking.searchPhase.phaseStartTime = currentTime;
                return { x: 0.0, y: 0.0, rot: 0.0 };
            }
            return { x: 0.0, y: 0.0, rot: PARKING_CONFIG.DRIVE.SEARCH_SPIN_SPEED };
        }
    }
    else if (STATE.parking.mode === 'INITIAL_PAUSE' || STATE.parking.mode === 'STOPPED') {
        return { x: 0.0, y: 0.0, rot: 0.0 };
    }
    else if (STATE.parking.mode === 'APPROACHING') {
        var rotation = calculateParkingRotation(dt, true);
        var forwardSpeed = 0.0;
        var absXError = Math.abs(STATE.parking.tracking.tagXError);
        if (absXError < PARKING_CONFIG.THRESHOLDS.MIDDLE_X_FORWARD) {
            var centeringAccuracy = 1.0 - (absXError / PARKING_CONFIG.THRESHOLDS.MIDDLE_X_FORWARD);
            forwardSpeed = PARKING_CONFIG.DRIVE.APPROACH_SPEED_MIN +
                (centeringAccuracy * (PARKING_CONFIG.DRIVE.APPROACH_SPEED_MAX - PARKING_CONFIG.DRIVE.APPROACH_SPEED_MIN));
        }
        if (forwardSpeed === 0.0 && rotation !== 0.0 && Math.abs(rotation) < PARKING_CONFIG.PID.MIN_OUTPUT) {
            rotation = rotation > 0 ? PARKING_CONFIG.PID.MIN_OUTPUT : -PARKING_CONFIG.PID.MIN_OUTPUT;
        }
        return { x: 0.0, y: forwardSpeed, rot: rotation };
    }
    else if (STATE.parking.mode === 'REVERSING') {
        var timeInReversePhase = currentTime - STATE.parking.reversePhase.phaseStartTime;
        if (STATE.parking.reversePhase.phase === 'PAUSED') {
            if (timeInReversePhase > PARKING_CONFIG.DRIVE.REVERSE_PAUSE_MS) {
                STATE.parking.reversePhase.phase = 'MOVING';
                STATE.parking.reversePhase.phaseStartTime = currentTime;
            }
            return { x: 0.0, y: 0.0, rot: 0.0 };
        } else {
            if (timeInReversePhase > PARKING_CONFIG.DRIVE.REVERSE_STEP_MS) {
                STATE.parking.reversePhase.phase = 'PAUSED';
                STATE.parking.reversePhase.phaseStartTime = currentTime;
                return { x: 0.0, y: 0.0, rot: 0.0 };
            }
            return { x: 0.0, y: PARKING_CONFIG.DRIVE.REVERSE_SPEED, rot: 0.0 };
        }
    }
    else if (STATE.parking.mode === 'TURNING_180') {
        return { x: 0.0, y: 0.0, rot: PARKING_CONFIG.DRIVE.TURN_180_SPEED };
    }
    else if (STATE.parking.mode === 'REVERSING_AGAIN') {
        return { x: 0.0, y: PARKING_CONFIG.DRIVE.REVERSE_SPEED, rot: 0.0 };
    }

    return { x: 0.0, y: 0.0, rot: 0.0 };
}

// ==========================================
// BUTTON CONTROL
// ==========================================
var buttonPressed = false;

onButton(function (state) {
    if (state === "pressed") {
        log("Button pressed - starting script");
        buttonPressed = true;
    } else if (state === "released") {
        log("Button released");
    }
});

// ==========================================
// MAIN CONTROL LOOP
// ==========================================
stopAprilTag();
startYolo();
playAudio('fardinho');

// Wait for button press
log("Waiting for button press to start...");
while (!buttonPressed) {
    try {
        wait(100);
    } catch (e) {
        console.log("Script interrupted or stopped: " + e);
        break;
    }
}

log("Starting ball tracking phase...");

while (true) {
    try {
        var currentTime = Date.now();
        var elapsedMs = currentTime - scriptStartTime;
        var dt = (currentTime - STATE.pid.lastTime) / 1000.0;
        STATE.pid.lastTime = currentTime;

        // Phase transition logic
        if (elapsedMs > SCRIPT_DURATION_MS) {
            // Total time exceeded, stop
            stop();
            break;
        } else if (elapsedMs > TRACKING_DURATION_MS) {
            // Switch to parking phase
            if (currentPhase === PHASE.BALL_TRACKING) {
                currentPhase = PHASE.PARKING;
                log("Transitioning to PARKING phase...");
                stopYolo();
                startAprilTag();
                STATE.parking.mode = 'SEARCHING';
                STATE.parking.searchPhase.phase = 'PAUSED';
                STATE.parking.searchPhase.phaseStartTime = currentTime;
            }
            var cmd = determineParkingVelocities(currentTime, dt);
            smartDrive(cmd.x, cmd.y, cmd.rot, currentTime);
            if (STATE.parking.mode === 'STOPPED') {
                log("Parking complete! Robot parked successfully.");
                break;
            }
        } else {
            // Ball tracking phase
            var cmd = determineBallVelocities(currentTime, dt);
            smartDrive(cmd.x, cmd.y, cmd.rot, currentTime);
        }
    } catch (e) {
        console.log("Script interrupted or stopped: " + e);
        break;
    }
}
