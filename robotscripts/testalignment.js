// --- CONFIGURATION ---
var PING_PONG_LABEL = 32;
var TARGET_APRILTAG_ID = 2; // HARDCODED TARGET ID
var CENTER_X = 0.5; 
var TARGET_CENTER_TOLERANCE = 0.1; 
var YAW_TOLERANCE_DEG = 1.0; // Margin for "parallel" alignment
var SIGHT_TIMEOUT_MS = 500;
var SWITCH_TO_TAG_MS = 150000; // 150 seconds

// --- STATES ---
var STATE_SEARCH_BALL = 'SEARCH_BALL';
var STATE_TRACK_BALL = 'TRACK_BALL';
var STATE_APPROACH_BALL = 'APPROACH_BALL';
var STATE_CAPTURE_DRIVE = 'CAPTURE_DRIVE';
var STATE_SEARCH_APRILTAG = 'SEARCH_APRILTAG';
var STATE_ALIGN_TO_TAG = 'ALIGN_TO_TAG';
var STATE_DRIVE_TO_BASE = 'DRIVE_TO_BASE';
var STATE_ROTATE_180 = 'ROTATE_180';
var STATE_DEPOSIT_BALLS = 'DEPOSIT_BALLS';
var STATE_DONE = 'DONE';

// --- GLOBAL VARIABLES ---
var currentState = STATE_SEARCH_BALL;
var startTime = Date.now();
var lastSeenBallTime = 0;
var lastSeenTagTime = 0;
var ballX = 0, ballY = 0;
var tagX = 0, tagYaw = 0, tagWidth = 0, tagHeight = 0;
var captureStartTime = 0;
var depositStartTime = 0;
var rotate180StartTime = 0;

// PID for Rotation
var kp_rot = 1.2;
var ki_rot = 0.0;
var kd_rot = 0.4;
var prevRotError = 0;
var rotIntegral = 0;

// PID/Gain for Strafing (Parallel alignment)
var kp_strafe = 0.5; 

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
                var area = d.get("w") * d.get("h");
                if (area > maxArea) {
                    maxArea = area;
                    bestBall = { x: d.get("x"), y: d.get("y") };
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
                tagX = tag.get("cx");
                tagYaw = tag.get("yaw"); // Rotation relative to camera face
                
                // Estimate distance/width from corners
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

// --- MAIN LOOP ---
startAprilTag();
startYolo();

while (true) {
    try {
        var now = Date.now();
        var ballVisible = (now - lastSeenBallTime < SIGHT_TIMEOUT_MS);
        var tagVisible = (now - lastSeenTagTime < SIGHT_TIMEOUT_MS);
        var matchTime = now - startTime;

        // Transition from Ball to Tag after 150s
        if (matchTime > SWITCH_TO_TAG_MS && 
            [STATE_SEARCH_APRILTAG, STATE_ALIGN_TO_TAG, STATE_DRIVE_TO_BASE, STATE_DEPOSIT_BALLS].indexOf(currentState) === -1) {
            log("150s elapsed: Switching to Home Base Search.");
            currentState = STATE_SEARCH_APRILTAG;
        }

        switch (currentState) {

            case STATE_SEARCH_APRILTAG:
                // Search rotation until target tag is found
                drive(0, 0, 0.3);
                if (tagVisible) {
                    log("Base Tag " + TARGET_APRILTAG_ID + " found! Locking on...");
                    currentState = STATE_ALIGN_TO_TAG;
                }
                break;

            case STATE_ALIGN_TO_TAG:
                if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }
                
                // 1. CALCULATE CENTERING ERROR (STRAFE)
                var centerError = tagX - CENTER_X;
                var strafeOutput = centerError * kp_strafe;

                // 2. CALCULATE YAW ERROR (ROTATE)
                var yawError = Number(tagYaw) || 0;
                var rotOutput = -(yawError / 45.0) * kp_rot;

                log("Aligning: CenterErr=" + centerError.toFixed(3) + " YawErr=" + yawError.toFixed(2));

                // Drive: Strafe to center, No forward, Rotate to match tag yaw
                drive(strafeOutput, 0, rotOutput);

                // Transition when both centered and parallel (tag yaw near 0)
                if (Math.abs(yawError) < YAW_TOLERANCE_DEG && Math.abs(centerError) < 0.05) {
                    log("Aligned! Transitioning to Drive to Base (Reverse).");
                    currentState = STATE_DRIVE_TO_BASE;
                }
                break;

            case STATE_DRIVE_TO_BASE:
                if (!tagVisible) { 
                    log("Lost tag in Drive to Base, hunting...");
                    drive(0, 0, 0); 
                    currentState = STATE_SEARCH_APRILTAG;
                    break;
                }

                // Maintain alignment while reversing
                var centerError = tagX - CENTER_X;
                var yawError = Number(tagYaw) || 0;
                var strafeOutput = centerError * kp_strafe;
                var rotOutput = -(yawError / 45.0) * kp_rot;

                // Reverse slowly (-0.2 forward) while maintaining centering and parallelism
                drive(strafeOutput, -0.2, rotOutput);
                
                log("Tag Height: " + tagHeight.toFixed(3));

                // Transition when tag takes up ~50% of vertical viewport
                if (tagHeight > 0.5) {
                    log("Arrived at target distance. Rotating 180 degrees...");
                    rotate180StartTime = now;
                    currentState = STATE_ROTATE_180;
                }
                break;

            case STATE_ROTATE_180:
                // Rotate 180 degrees (timed/approx for now or use IMU if available)
                drive(0, 0, 0.5); 
                if (now - rotate180StartTime > 1500) { // Approx 1.5s for 180 at 0.5 power
                    log("Rotation complete. Final approach...");
                    depositStartTime = now;
                    currentState = STATE_DEPOSIT_BALLS;
                }
                break;

            case STATE_DEPOSIT_BALLS:
                drive(0, 0.3, 0); // Final short drive to base wall after rotation
                if (now - depositStartTime > 1000) {
                    drive(0, 0, 0);
                    lift(UP, 0.6);
                    wait(3000);
                    lift(DOWN, 0.6);
                    log("Mission Complete.");
                    currentState = STATE_DONE;
                }
                break;

            case STATE_DONE:
                drive(0, 0, 0);
                wait(1000);
                break;
        }

        wait(10);
    } catch (e) {
        log("Runtime Error: " + e);
        break;
    }
}
