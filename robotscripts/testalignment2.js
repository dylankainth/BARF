// --- CONFIGURATION ---
var PING_PONG_LABEL = 32;
var TARGET_APRILTAG_ID = 20; 
var CAMERA_OFFSET_MULTIPLIER = 1.1; 
var CENTER_X = 0.5; 
var TARGET_CENTER_TOLERANCE = 0.1; 
var YAW_TOLERANCE_DEG = 1.0; 
var SIGHT_TIMEOUT_MS = 500;

// --- DISTANCE P-LOOP CONFIG ---
var TARGET_TAG_SIZE = 0.50;      // Target height % of viewport
var DISTANCE_TOLERANCE = 0.03;   // Stop within 3% of target
var kp_distance = 0.8;           // P-gain for forward/backward
var MAX_APPROACH_SPEED = 0.4;    // Speed cap

// --- ROTATION PID VARIABLES ---
var kp_rot = 1.2;
var ki_rot = 0.0;
var kd_rot = 0.4;
var prevRotError = 0;
var rotIntegral = 0;

// --- STRAFE PID VARIABLES ---
var kp_strafe = 0.9; 
var ki_strafe = 0.3; 
var kd_strafe = 0.05; 
var strafeIntegralClamp = 0.5; 
var prevStrafeError = 0;
var strafeIntegral = 0;

// SmartDrive Config
var RATE_LIMIT_MS = 40;
var EPSILON = 0.01;

// --- STATES ---
var STATE_SEARCH_APRILTAG = 'SEARCH_APRILTAG';
var STATE_ALIGN_TO_TAG = 'ALIGN_TO_TAG';
var STATE_ADJUST_DISTANCE = 'ADJUST_DISTANCE'; 
var STATE_ROTATE_180 = 'ROTATE_180';
var STATE_DEPOSIT_BALLS = 'DEPOSIT_BALLS';
var STATE_DONE = 'DONE';

// --- GLOBAL VARIABLES ---
var currentState = STATE_SEARCH_APRILTAG;
var startTime = Date.now();
var lastTime = Date.now(); 
var lastSeenTagTime = 0;
var tagX = 0, tagYaw = 0, tagWidth = 0, tagHeight = 0;
var depositStartTime = 0;
var rotate180StartTime = 0;

var hardwareState = { lastSentX: null, lastSentY: null, lastSentRot: null, lastCommandTime: 0 };

// --- UTILITIES ---
function clamp(v, min, max) { return v < min ? min : v > max ? max : v; }

function smartDrive(x, y, rot, currentTime) {
    var hw = hardwareState;
    if (currentTime - hw.lastCommandTime < RATE_LIMIT_MS) return; 
    var xChanged = hw.lastSentX === null || Math.abs(x - hw.lastSentX) > EPSILON;
    var yChanged = hw.lastSentY === null || Math.abs(y - hw.lastSentY) > EPSILON;
    var rotChanged = hw.lastSentRot === null || Math.abs(rot - hw.lastSentRot) > EPSILON;
    if (xChanged || yChanged || rotChanged) {
        drive(x, y, rot); 
        hw.lastSentX = x; hw.lastSentY = y; hw.lastSentRot = rot; hw.lastCommandTime = currentTime;
    }
}

// --- SENSOR HOOKS ---
onDetection(function(dets) {
    if (!dets) return;
    var apriltags = dets.get("apriltags");
    if (apriltags) {
        for (var j = 0; j < apriltags.size(); j++) {
            var tag = apriltags.get(j);
            if (Math.trunc(tag.get("id")) === TARGET_APRILTAG_ID) {
                tagYaw = tag.get("yaw");
                var corners = tag.get("corners");
                if (corners) {
                    var minX = 1.0, maxX = 0.0, minY = 1.0, maxY = 0.0;
                    for (var c = 0; c < 4; c++) {
                        var cx = corners.get(c).get(0); var cy = corners.get(c).get(1);
                        if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                        if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
                    }
                    tagWidth = maxX - minX; tagHeight = maxY - minY;
                }
                tagX = tag.get("cx") + (tagWidth * 0.5) + (tagWidth * CAMERA_OFFSET_MULTIPLIER);
                lastSeenTagTime = Date.now();
                break;
            }
        }
    }
});

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
stopYolo();

while (true) {
    try {
        var now = Date.now();
        var dt = (now - lastTime) / 1000.0; 
        lastTime = now;
        var tagVisible = (now - lastSeenTagTime < SIGHT_TIMEOUT_MS);

        switch (currentState) {
            case STATE_SEARCH_APRILTAG:
                smartDrive(0, 0, 0.18, now);
                if (tagVisible) { 
                    strafeIntegral = 0; 
                    rotIntegral = 0;
                    currentState = STATE_ALIGN_TO_TAG; 
                }
                break;

            case STATE_ALIGN_TO_TAG:
                if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }
                
                var centerErr = tagX - CENTER_X;
                var yawErr = tagYaw;
                
                var isCenter = Math.abs(centerErr) <= TARGET_CENTER_TOLERANCE;
                var isYaw = Math.abs(yawErr) <= YAW_TOLERANCE_DEG;

                // Calculate full PID for both axes
                var sOut = calculateStrafePID(centerErr, dt);
                var rOut = calculateRotationPID(yawErr);

                // If an axis is already "good", we can zero it out to prevent jitter
                if (isCenter) sOut = 0;
                if (isYaw) rOut = 0;

                smartDrive(sOut, 0, rOut, now);

                if (isCenter && isYaw) {
                    log("Aligned. Adjusting distance...");
                    currentState = STATE_ADJUST_DISTANCE;
                }
                break;

            case STATE_ADJUST_DISTANCE:
                if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }
                
                // P-Loop for Forward/Backward Distance
                var sizeErr = TARGET_TAG_SIZE - tagHeight;
                var forwardOut = clamp(sizeErr * kp_distance, -MAX_APPROACH_SPEED, MAX_APPROACH_SPEED);

                // Use existing PID functions to MAINTAIN centering and yaw while moving
                var sOut = calculateStrafePID(tagX - CENTER_X, dt);
                var rOut = calculateRotationPID(tagYaw);

                smartDrive(sOut, forwardOut, rOut, now);

                if (Math.abs(sizeErr) <= DISTANCE_TOLERANCE) {
                    log("Distance reached. Rotating 180...");
                    rotate180StartTime = now;
                    currentState = STATE_ROTATE_180;
                }
                break;

            case STATE_ROTATE_180:
                smartDrive(0, 0, 0.5, now); 
                if (now - rotate180StartTime > 1500) {
                    depositStartTime = now;
                    // currentState = STATE_DEPOSIT_BALLS;
                    currentState = STATE_DONE;
                }
                break;

            case STATE_DEPOSIT_BALLS:
                smartDrive(0, 0.3, 0, now); 
                if (now - depositStartTime > 1000) {
                    smartDrive(0, 0, 0, now);
                    lift(UP, 0.6); wait(3000); lift(DOWN, 0.6);
                    currentState = STATE_DONE;
                }
                break;

            case STATE_DONE:
                smartDrive(0, 0, 0, now);
                break;
        }
        wait(50);
    } catch (e) { log("Error: " + e); break; }
}