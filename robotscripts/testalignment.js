// --- CONFIGURATION ---
var PING_PONG_LABEL = 32;
var TARGET_APRILTAG_ID = 2; // HARDCODED TARGET ID
var CAMERA_OFFSET_MULTIPLIER = 1.5; // Added from second script
var CENTER_X = 0.5; 
var TARGET_CENTER_TOLERANCE = 0.1; 
var YAW_TOLERANCE_DEG = 1.0; // Margin for "parallel" alignment
var SIGHT_TIMEOUT_MS = 500;
var SWITCH_TO_TAG_MS = 150000; // 150 seconds

// SmartDrive Config
var RATE_LIMIT_MS = 40;
var EPSILON = 0.01;

// --- STATES ---
var STATE_SEARCH_APRILTAG = 'SEARCH_APRILTAG';
var STATE_ALIGN_TO_TAG = 'ALIGN_TO_TAG';
var STATE_DRIVE_TO_BASE = 'DRIVE_TO_BASE';
var STATE_ROTATE_180 = 'ROTATE_180';
var STATE_DEPOSIT_BALLS = 'DEPOSIT_BALLS';
var STATE_DONE = 'DONE';

// --- GLOBAL VARIABLES ---
var currentState = STATE_SEARCH_APRILTAG;
var startTime = Date.now();
var lastTime = Date.now(); // Added for dt calculation
var lastSeenBallTime = 0;
var lastSeenTagTime = 0;
var ballX = 0, ballY = 0;
var tagX = 0, tagYaw = 0, tagWidth = 0, tagHeight = 0;
var captureStartTime = 0;
var depositStartTime = 0;
var rotate180StartTime = 0;

// Hardware State for SmartDrive
var hardwareState = {
    lastSentX: null,
    lastSentY: null,
    lastSentRot: null,
    lastCommandTime: 0
};

// PID for Rotation
var kp_rot = 1.2;
var ki_rot = 0.0;
var kd_rot = 0.4;
var prevRotError = 0;
var rotIntegral = 0;

// PID for Strafing (Parallel alignment)
var kp_strafe = 0.9; 
var ki_strafe = 0.3; 
var kd_strafe = 0.05; 
var strafeIntegralClamp = 0.5; 
var prevStrafeError = 0;
var strafeIntegral = 0;

// --- UTILITY FUNCTIONS ---
function clamp(value, min, max) {
    return value < min ? min : value > max ? max : value;
}

// --- HARDWARE CONTROL ---
function smartDrive(x, y, rot, currentTime) {
    var hw = hardwareState;
    if (currentTime - hw.lastCommandTime < RATE_LIMIT_MS) return; 

    var xChanged   = hw.lastSentX === null   || Math.abs(x - hw.lastSentX) > EPSILON;
    var yChanged   = hw.lastSentY === null   || Math.abs(y - hw.lastSentY) > EPSILON;
    var rotChanged = hw.lastSentRot === null || Math.abs(rot - hw.lastSentRot) > EPSILON;

    if (xChanged || yChanged || rotChanged) {
        drive(x, y, rot); 
        hw.lastSentX = x;
        hw.lastSentY = y;
        hw.lastSentRot = rot;
        hw.lastCommandTime = currentTime;
    }
}

// --- SENSOR HOOKS ---
onDetection(function(dets) {
    if (!dets) return;
    
    // Process YOLO
    var yolodets = dets.get("yolo");
    if (yolodets) {
        var bestBall = null;
        var maxArea = 0;
        for (var i = 0; i < Math.trunc(yolodets.size()); i++) {
            var d = yolodets.get(i);
            if (Math.trunc(d.get("label")) === PING_PONG_LABEL) {
                var width = parseFloat(d.get("w"));
                var area = width * parseFloat(d.get("h"));
                
                // Apply camera offset multiplier logic to YOLO
                var rawX = parseFloat(d.get("x"));
                var adjustedX = rawX + (width * 0.5) + (width * CAMERA_OFFSET_MULTIPLIER);

                if (area > maxArea) {
                    maxArea = area;
                    bestBall = { x: adjustedX, y: parseFloat(d.get("y")) };
                }
            }
        }
        if (bestBall) {
            ballX = bestBall.x;
            ballY = bestBall.y;
            lastSeenBallTime = Date.now();
        }
    }

    // Process AprilTag
    var apriltags = dets.get("apriltags");
    if (apriltags) {
        for (var j = 0; j < apriltags.size(); j++) {
            var tag = apriltags.get(j);
            if (Math.trunc(tag.get("id")) === TARGET_APRILTAG_ID) {
                tagYaw = tag.get("yaw"); // Rotation relative to camera face
                
                // Estimate distance/width from corners first (needed for the offset math)
                var corners = tag.get("corners");
                if (corners) {
                    var minX = 1.0, maxX = 0.0, minY = 1.0, maxY = 0.0;
                    for (var c = 0; c < 4; c++) {
                        var cx = corners.get(c).get(0);
                        var cy = corners.get(c).get(1);
                        if (cx < minX) minX = cx;
                        if (cx > maxX) maxX = cx;
                        if (cy < minY) minY = cy;
                        if (cy > maxY) maxY = cy;
                    }
                    tagWidth = maxX - minX;
                    tagHeight = maxY - minY;
                }

                // Apply camera offset multiplier logic to AprilTag
                // tag.get("cx") is already center, so we just add the offset
                var rawCx = tag.get("cx");
                tagX = rawCx + (tagWidth * CAMERA_OFFSET_MULTIPLIER);

                lastSeenTagTime = Date.now();
                break;
            }
        }
    }
});

// --- PID CONTROLLERS ---
function calculateRotationPID(error) {
    rotIntegral += error;
    var derivative = error - prevRotError;
    prevRotError = error;
    return (kp_rot * error) + (ki_rot * rotIntegral) + (kd_rot * derivative);
}

function calculateStrafePID(error, dt) {
    if (dt > 0) {
        strafeIntegral += error * dt;
        strafeIntegral = clamp(strafeIntegral, -strafeIntegralClamp, strafeIntegralClamp);
    }
    
    var derivative = dt > 0 ? (error - prevStrafeError) / dt : 0;
    prevStrafeError = error;
    
    return (kp_strafe * error) + (ki_strafe * strafeIntegral) + (kd_strafe * derivative);
}

// --- MAIN LOOP ---
startAprilTag();
// startYolo();
stopYolo();

while (true) {
    try {
        var now = Date.now();
        var dt = (now - lastTime) / 1000.0; 
        lastTime = now;
        
        var tagVisible = (now - lastSeenTagTime < SIGHT_TIMEOUT_MS);
        var matchTime = now - startTime;

        switch (currentState) {

            case STATE_SEARCH_APRILTAG:
                // Search rotation until target tag is found
                smartDrive(0, 0, 0.15, now);
                if (tagVisible) {
                    log("Base Tag " + TARGET_APRILTAG_ID + " found! Locking on...");
                    strafeIntegral = 0; 
                    currentState = STATE_ALIGN_TO_TAG;
                }
                break;
            case STATE_ALIGN_TO_TAG:
                if (!tagVisible) { 
                    currentState = STATE_SEARCH_APRILTAG; 
                    break; 
                }
                
                // 1. Guard against NaN by forcing a fallback to 0
                var safeTagX = parseFloat(tagX) || 0;
                var safeTagYaw = parseFloat(tagYaw) || 0;

                var centerError = safeTagX - CENTER_X;
                var yawError = safeTagYaw;
                
                var strafeOutput = 0;
                var rotOutput = 0;

                // 2. Check alignments independently
                var isCenterAligned = Math.abs(centerError) <= TARGET_CENTER_TOLERANCE;
                var isYawAligned = Math.abs(yawError) <= YAW_TOLERANCE_DEG;

                // 3. Calculate outputs concurrently (no if/else bottleneck)
                if (!isCenterAligned) {
                    strafeOutput = calculateStrafePID(centerError, dt);
                    log("Aligning [Strafing]: CenterErr=" + centerError.toFixed(3));
                } 
                
                if (!isYawAligned) {
                    // 4. Use the custom PID function you wrote!
                    rotOutput = calculateRotationPID(yawError);
                    log("Aligning [Rotating]: YawErr=" + yawError.toFixed(2));
                }

                // Apply both movements at the same time for smooth diagonal/curved corrections
                smartDrive(strafeOutput, 0, rotOutput, now);

                // Transition only when BOTH conditions are fully met
                if (isCenterAligned && isYawAligned) {
                    log("Aligned! Transitioning to Drive to Base (Reverse).");
                    currentState = STATE_DRIVE_TO_BASE;
                }
                break;

            case STATE_DRIVE_TO_BASE:
                if (!tagVisible) { 
                    log("Lost tag in Drive to Base, hunting...");
                    smartDrive(0, 0, 0, now); 
                    currentState = STATE_SEARCH_APRILTAG;
                    break;
                }

                var centerError = tagX - CENTER_X;
                var yawError = Number(tagYaw) || 0;
                
                var strafeOutput = calculateStrafePID(centerError, dt);
                var rotOutput = -(yawError / 45.0) * kp_rot;

                // Reverse slowly (-0.2 forward) while maintaining centering and parallelism
                smartDrive(strafeOutput, 0.2, rotOutput, now);
                
                log("Tag Height: " + tagHeight.toFixed(3));

                if (tagHeight > 0.5) {
                    log("Arrived at target distance. Rotating 180 degrees...");
                    rotate180StartTime = now;
                    currentState = STATE_ROTATE_180;
                }
                break;

            case STATE_ROTATE_180:
                // Rotate 180 degrees (timed/approx for now or use IMU if available)
                smartDrive(0, 0, 0.5, now); 
                if (now - rotate180StartTime > 1500) { 
                    log("Rotation complete. Final approach...");
                    depositStartTime = now;
                    currentState = STATE_DEPOSIT_BALLS;
                }
                break;

            case STATE_DEPOSIT_BALLS:
                smartDrive(0, 0.3, 0, now); // Final short drive to base wall after rotation
                if (now - depositStartTime > 1000) {
                    smartDrive(0, 0, 0, now);
                    lift(UP, 0.6);
                    wait(3000);
                    lift(DOWN, 0.6);
                    log("Mission Complete.");
                    currentState = STATE_DONE;
                }
                break;

            case STATE_DONE:
                smartDrive(0, 0, 0, now);
                wait(1000);
                break;
        }

        wait(50);
        log(currentState)
    } catch (e) {
        log("Runtime Error: " + e);
        break;
    }
}