// ==========================================
// CONFIGURATION
// ==========================================
var CONFIG = {
    VISION: {
        TARGET_APRILTAG_ID: 20, 
        CAMERA_OFFSET_MULTIPLIER: 1.1,
        SIGHT_TIMEOUT_MS: 3000   // TIP: Lower this if you want it to reverse faster after losing the tag
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
        
        // Search Step-and-Stop Timings
        STEP_TIME_MS: 100,       
        PAUSE_TIME_MS: 300,      
        
        // Tag Found Timing
        INITIAL_PAUSE_MS: 1500,
        
        // --- NEW: Reverse Timings & Speeds ---
        REVERSE_SPEED: -0.3,          // Speed to drive backwards (negative Y)
        REVERSE_STEP_MS: 400,         // How long to drive backwards per interval
        REVERSE_PAUSE_MS: 600,        // How long to pause and look per interval
        MAX_REVERSE_TIME_MS: 6000     // Give up and search again after 6 seconds of reversing
    }
};

// ==========================================
// RUNTIME STATE
// ==========================================
var STATE = {
    mode: 'SEARCHING', // SEARCHING, INITIAL_PAUSE, APPROACHING, REVERSING, STOPPED
    tracking: {
        lastSeenTime: 0,
        firstSeenTime: 0,
        tagXError: 0.0
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
    searchPhase: {
        phase: 'PAUSED', 
        phaseStartTime: 0
    },
    reversePhase: {
        phase: 'PAUSED', 
        phaseStartTime: 0,
        totalStartTime: 0
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
onDetection(function(dets) {
    if (!dets) return;
    
    var apriltags = dets.get("apriltags");
    if (apriltags) {
        for (var j = 0; j < apriltags.size(); j++) {
            var tag = apriltags.get(j);
            
            if (Math.trunc(tag.get("id")) === CONFIG.VISION.TARGET_APRILTAG_ID) {
                STATE.tracking.lastSeenTime = Date.now();
                
                var cx = parseFloat(tag.get("cx")); 
                var w = parseFloat(tag.get("w")); 

                if (!isNaN(cx)) {
                    var widthOffset = !isNaN(w) ? (w * CONFIG.VISION.CAMERA_OFFSET_MULTIPLIER) : 0.0;
                    var adjustedCx = cx + widthOffset;
                    STATE.tracking.tagXError = (adjustedCx - 0.5) * 2.0; 
                }
                break; 
            }
        }
    }
});

function calculateRotation(dt, isTargetValid) {
    if (!isTargetValid) {
        STATE.pid.integral = 0.0;
        STATE.pid.lastError = 0.0;
        return 0.0;
    }
    
    if (STATE.tracking.tagXError * STATE.pid.lastError < 0) {
        STATE.pid.integral = 0.0;
    }
    
    if (dt > 0) {
        STATE.pid.integral += STATE.tracking.tagXError * dt;
        STATE.pid.integral = clamp(STATE.pid.integral, -CONFIG.PID.I_CLAMP, CONFIG.PID.I_CLAMP);
    }
    
    var rawRotation = (STATE.tracking.tagXError * CONFIG.PID.KP) + (STATE.pid.integral * CONFIG.PID.KI);
    STATE.pid.lastError = STATE.tracking.tagXError;
    
    return clamp(rawRotation, -CONFIG.PID.OUTPUT_CLAMP, CONFIG.PID.OUTPUT_CLAMP);
}

// ==========================================
// MOVEMENT LOGIC
// ==========================================
function determineVelocities(currentTime, dt) {
    var timeSinceSeen = currentTime - STATE.tracking.lastSeenTime;
    var isVisible = timeSinceSeen < CONFIG.VISION.SIGHT_TIMEOUT_MS;

    // --- 1. STATE TRANSITIONS ---
    if (!isVisible) {
        if (STATE.mode === 'APPROACHING') {
            // Lost tag while approaching -> Start reversing
            STATE.mode = 'REVERSING';
            STATE.reversePhase.totalStartTime = currentTime;
            STATE.reversePhase.phase = 'PAUSED';
            STATE.reversePhase.phaseStartTime = currentTime;
            console.log("Lost tag during approach. Reversing to reacquire...");
        } 
        else if (STATE.mode === 'REVERSING') {
            // Check if we've been reversing for too long
            if (currentTime - STATE.reversePhase.totalStartTime > CONFIG.DRIVE.MAX_REVERSE_TIME_MS) {
                STATE.mode = 'SEARCHING';
                STATE.searchPhase.phase = 'PAUSED';
                STATE.searchPhase.phaseStartTime = currentTime;
                console.log("Reverse timeout reached. Resuming search...");
            }
        } 
        else if (STATE.mode !== 'SEARCHING' && STATE.mode !== 'REVERSING') {
            // Failsafe: if we were stopped or pausing and lost it entirely
            STATE.mode = 'SEARCHING';
            STATE.searchPhase.phase = 'PAUSED';
            STATE.searchPhase.phaseStartTime = currentTime;
            console.log("Tag lost. Searching...");
        }
    } else {
        // Tag IS visible
        if (STATE.mode === 'SEARCHING') {
            STATE.mode = 'INITIAL_PAUSE';
            STATE.tracking.firstSeenTime = currentTime;
            STATE.pid.integral = 0.0; 
            console.log("Tag found! Pausing...");
        } 
        else if (STATE.mode === 'REVERSING') {
            // Successfully reacquired tag while reversing
            STATE.mode = 'STOPPED';
            console.log("Tag reacquired! Stopping.");
        } 
        else if (STATE.mode === 'INITIAL_PAUSE' && (currentTime - STATE.tracking.firstSeenTime > CONFIG.DRIVE.INITIAL_PAUSE_MS)) {
            STATE.mode = 'APPROACHING';
            console.log("Approaching tag...");
        }
    }

    // --- 2. MOVEMENT COMMANDS BASED ON STATE ---
    if (STATE.mode === 'SEARCHING') {
        var timeInPhase = currentTime - STATE.searchPhase.phaseStartTime;
        
        if (STATE.searchPhase.phase === 'PAUSED') {
            if (timeInPhase > CONFIG.DRIVE.PAUSE_TIME_MS) {
                STATE.searchPhase.phase = 'MOVING';
                STATE.searchPhase.phaseStartTime = currentTime;
            }
            return { x: 0.0, y: 0.0, rot: 0.0 };
        } else {
            if (timeInPhase > CONFIG.DRIVE.STEP_TIME_MS) {
                STATE.searchPhase.phase = 'PAUSED';
                STATE.searchPhase.phaseStartTime = currentTime;
                return { x: 0.0, y: 0.0, rot: 0.0 };
            }
            return { x: 0.0, y: 0.0, rot: CONFIG.DRIVE.SEARCH_SPIN_SPEED };
        }
    } 
    else if (STATE.mode === 'INITIAL_PAUSE' || STATE.mode === 'STOPPED') {
        // Hold still
        return { x: 0.0, y: 0.0, rot: 0.0 };
    } 
    else if (STATE.mode === 'APPROACHING') {
        var rotation = calculateRotation(dt, true);
        var forwardSpeed = 0.0;
        var absXError = Math.abs(STATE.tracking.tagXError);

        if (absXError < CONFIG.THRESHOLDS.MIDDLE_X_FORWARD) {
            var centeringAccuracy = 1.0 - (absXError / CONFIG.THRESHOLDS.MIDDLE_X_FORWARD);
            forwardSpeed = CONFIG.DRIVE.APPROACH_SPEED_MIN + 
                           (centeringAccuracy * (CONFIG.DRIVE.APPROACH_SPEED_MAX - CONFIG.DRIVE.APPROACH_SPEED_MIN));
        }

        if (forwardSpeed === 0.0 && rotation !== 0.0 && Math.abs(rotation) < CONFIG.PID.MIN_OUTPUT) {
            rotation = rotation > 0 ? CONFIG.PID.MIN_OUTPUT : -CONFIG.PID.MIN_OUTPUT;
        }

        return { x: 0.0, y: forwardSpeed, rot: rotation };
    }
    else if (STATE.mode === 'REVERSING') {
        var timeInReversePhase = currentTime - STATE.reversePhase.phaseStartTime;
        
        if (STATE.reversePhase.phase === 'PAUSED') {
            if (timeInReversePhase > CONFIG.DRIVE.REVERSE_PAUSE_MS) {
                STATE.reversePhase.phase = 'MOVING';
                STATE.reversePhase.phaseStartTime = currentTime;
            }
            return { x: 0.0, y: 0.0, rot: 0.0 };
        } else {
            if (timeInReversePhase > CONFIG.DRIVE.REVERSE_STEP_MS) {
                STATE.reversePhase.phase = 'PAUSED';
                STATE.reversePhase.phaseStartTime = currentTime;
                return { x: 0.0, y: 0.0, rot: 0.0 };
            }
            // Drive straight backwards
            return { x: 0.0, y: CONFIG.DRIVE.REVERSE_SPEED, rot: 0.0 };
        }
    }

    return { x: 0.0, y: 0.0, rot: 0.0 }; 
}

// ==========================================
// MAIN CONTROL LOOP
// ==========================================
stopYolo();       
startAprilTag();  

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