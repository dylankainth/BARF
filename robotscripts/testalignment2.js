// --- CONFIGURATION ---
var PING_PONG_LABEL = 32;
var TARGET_APRILTAG_ID = 2; 
var CENTER_X = 0.5; 
var TARGET_CENTER_TOLERANCE = 0.05; 
var YAW_TOLERANCE_DEG = 1.0; 
var TARGET_TAG_SIZE = 0.50;      // Target height as % of viewport
var DISTANCE_TOLERANCE = 0.05;   // How close to 50% we need to be
var FORWARD_SPEED = 0.2;         // Fixed speed for distance adjustment
var SIGHT_TIMEOUT_MS = 500;
var SWITCH_TO_TAG_MS = 150000; 
var kp_distance = 0.8; // Proportional gain for distance
var MAX_SPEED = 0.25;    // Safety cap so it doesn't zoom too fast

// --- STATES ---
var STATE_SEARCH_BALL = 'SEARCH_BALL';
var STATE_TRACK_BALL = 'TRACK_BALL';
var STATE_APPROACH_BALL = 'APPROACH_BALL';
var STATE_CAPTURE_DRIVE = 'CAPTURE_DRIVE';
var STATE_SEARCH_APRILTAG = 'SEARCH_APRILTAG';
var STATE_ALIGN_TO_TAG = 'ALIGN_TO_TAG';      // Phase 1: Center horizontally
var STATE_ADJUST_DISTANCE = 'ADJUST_DISTANCE'; // Phase 2: Move to depth
var STATE_FINALIZE_YAW = 'FINALIZE_YAW';       // Phase 3: Get parallel
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
var rotate180StartTime = 0;
var depositStartTime = 0;

// PID / Gains
var kp_rot = 1.2;
var kp_strafe = 0.5; 

// --- SENSOR HOOKS ---
onDetection(function(dets) {
    if (!dets) return;
    
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
        if (bestBall) { ballX = bestBall.x; ballY = bestBall.y; lastSeenBallTime = Date.now(); }
    }

    var apriltags = dets.get("apriltags");
    if (apriltags) {
        for (var j = 0; j < apriltags.size(); j++) {
            var tag = apriltags.get(j);
            if (Math.trunc(tag.get("id")) === TARGET_APRILTAG_ID) {
                tagX = tag.get("cx");
                tagYaw = tag.get("yaw");
                var corners = tag.get("corners");
                if (corners) {
                    var minY = 1.0, maxY = 0.0;
                    for (var c = 0; c < 4; c++) {
                        var cy = corners.get(c).get(1);
                        if (cy < minY) minY = cy;
                        if (cy > maxY) maxY = cy;
                    }
                    tagHeight = maxY - minY;
                }
                lastSeenTagTime = Date.now();
                break;
            }
        }
    }
});

// --- MAIN LOOP ---
startAprilTag();
startYolo();

while (true) {
    try {
        var now = Date.now();
        var tagVisible = (now - lastSeenTagTime < SIGHT_TIMEOUT_MS);
        var matchTime = now - startTime;

        if (matchTime > SWITCH_TO_TAG_MS && 
            [STATE_SEARCH_APRILTAG, STATE_ALIGN_TO_TAG, STATE_ADJUST_DISTANCE, STATE_FINALIZE_YAW, STATE_ROTATE_180, STATE_DEPOSIT_BALLS].indexOf(currentState) === -1) {
            currentState = STATE_SEARCH_APRILTAG;
        }

        switch (currentState) {
            case STATE_SEARCH_APRILTAG:
                drive(0, 0, 0.3);
                if (tagVisible) currentState = STATE_ALIGN_TO_TAG;
                break;

            case STATE_ALIGN_TO_TAG:
                if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }
                var centerError = tagX - CENTER_X;
                drive(centerError * kp_strafe, 0, 0); // Only strafe
                if (Math.abs(centerError) < TARGET_CENTER_TOLERANCE) {
                    log("Centered. Adjusting distance...");
                    currentState = STATE_ADJUST_DISTANCE;
                }
                break;

            // case STATE_ADJUST_DISTANCE:
            //     if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }
            //     var sizeError = TARGET_TAG_SIZE - tagHeight;
            //     var moveDir = 0;

            //     // Move forward if too far (size small), backward if too close (size large)
            //     if (Math.abs(sizeError) > DISTANCE_TOLERANCE) {
            //         moveDir = (sizeError > 0) ? FORWARD_SPEED : -FORWARD_SPEED;
            //     }

            //     // Maintain center while moving
            //     drive((tagX - CENTER_X) * kp_strafe, moveDir, 0);

            //     if (Math.abs(sizeError) <= DISTANCE_TOLERANCE) {
            //         log("Distance reached. Finalizing Yaw...");
            //         currentState = STATE_FINALIZE_YAW;
            //     }
            //     break;


            case STATE_ADJUST_DISTANCE:
                if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }

                // 1. Calculate the Error
                // Positive error = too far (need to go forward)
                // Negative error = too close (need to go backward)
                var sizeError = TARGET_TAG_SIZE - tagHeight;

                // 2. Calculate P-Output
                var p_output = sizeError * kp_distance;

                // 3. Clamp the speed for safety (don't exceed MAX_SPEED)
                if (p_output > MAX_SPEED) p_output = MAX_SPEED;
                if (p_output < -MAX_SPEED) p_output = -MAX_SPEED;

                // 4. Maintain centering (strafe) while moving forward/back
                var centerError = tagX - CENTER_X;
                var strafeOutput = centerError * kp_strafe;

                drive(strafeOutput, p_output, 0);

                log("P-Dist: Err=" + sizeError.toFixed(3) + " Power=" + p_output.toFixed(2));

                // 5. Transition when the error is tiny
                if (Math.abs(sizeError) <= DISTANCE_TOLERANCE) {
                    drive(0, 0, 0); // Stop briefly
                    log("Distance set. Transitioning to Final Yaw.");
                    currentState = STATE_FINALIZE_YAW;
                }
                break;

            case STATE_FINALIZE_YAW:
                if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }
                var yawError = Number(tagYaw) || 0;
                var rotOutput = -(yawError / 45.0) * kp_rot;
                
                // Final hold on center and distance while rotating to parallel
                drive((tagX - CENTER_X) * kp_strafe, 0, rotOutput);

                if (Math.abs(yawError) < YAW_TOLERANCE_DEG) {
                    log("Fully aligned. Starting 180 turn.");
                    rotate180StartTime = now;
                    currentState = STATE_ROTATE_180;
                }
                break;

            case STATE_ROTATE_180:
                drive(0, 0, 0.5);
                if (now - rotate180StartTime > 1500) {
                    depositStartTime = now;
                    currentState = STATE_DEPOSIT_BALLS;
                }
                break;

            case STATE_DEPOSIT_BALLS:
                drive(0, 0.3, 0); 
                if (now - depositStartTime > 1000) {
                    drive(0, 0, 0);
                    lift(UP, 0.6);
                    wait(3000);
                    lift(DOWN, 0.6);
                    currentState = STATE_DONE;
                }
                break;

            case STATE_DONE:
                drive(0, 0, 0);
                break;
        }
        wait(10);
    } catch (e) {
        log("Error: " + e);
        break;
    }
}