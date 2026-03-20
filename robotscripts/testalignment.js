// --- CONFIGURATION ---
var PING_PONG_LABEL = 32;
var TARGET_APRILTAG_ID = 42; // HARDCODED TARGET ID
var CENTER_X = 0.5; 
var TARGET_CENTER_TOLERANCE = 0.1; 
var YAW_TOLERANCE_DEG = 3.0; // Margin for "parallel" alignment
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
var STATE_DEPOSIT_BALLS = 'DEPOSIT_BALLS';
var STATE_DONE = 'DONE';

// --- GLOBAL VARIABLES ---
var currentState = STATE_SEARCH_BALL;
var startTime = Date.now();
var lastSeenBallTime = 0;
var lastSeenTagTime = 0;
var ballX = 0, ballY = 0;
var tagX = 0, tagYaw = 0, tagWidth = 0;
var captureStartTime = 0;
var depositStartTime = 0;

// PID for Rotation
var kp_rot = 1.2;
var ki_rot = 0.0;
var kd_rot = 0.4;
var prevRotError = 0;
var rotIntegral = 0;

// PID/Gain for Strafing (Parallel alignment)
var kp_strafe = 0.5; 

// --- HELPER FUNCTIONS ---

// Function to convert quaternion to Euler angles (Yaw only)
function getYawFromQuaternion() {
    var q = getQuaternion(); // Assuming this returns [x, y, z, w]
    if (!q || q.length < 4) return 0;
    
    var x = q[0], y = q[1], z = q[2], w = q[3];
    
    // Euler yaw (Z-axis rotation) calculation
    var siny_cosp = 2 * (w * z + x * y);
    var cosy_cosp = 1 - 2 * (y * y + z * z);
    var yaw_rad = Math.atan2(siny_cosp, cosy_cosp);
    
    return yaw_rad * (180.0 / Math.PI);
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
                    var minX = 1.0, maxX = 0.0;
                    for (var c = 0; c < 4; c++) {
                        var cx = corners.get(c).get(0);
                        if (cx < minX) minX = cx;
                        if (cx > maxX) maxX = cx;
                    }
                    tagWidth = maxX - minX;
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
            case STATE_SEARCH_BALL:
                drive(0, 0, 0.3); // Spin search
                if (ballVisible) currentState = STATE_TRACK_BALL;
                break;

            case STATE_TRACK_BALL:
                if (!ballVisible) { currentState = STATE_SEARCH_BALL; break; }
                var rot = calculateRotationPID(ballX - CENTER_X);
                drive(0, 0.2, rot);
                if (Math.abs(ballX - CENTER_X) < TARGET_CENTER_TOLERANCE) currentState = STATE_APPROACH_BALL;
                break;

            case STATE_APPROACH_BALL:
                if (!ballVisible) { 
                    captureStartTime = now; 
                    currentState = STATE_CAPTURE_DRIVE; 
                    break; 
                }
                var rot = calculateRotationPID(ballX - CENTER_X);
                drive(0, 0.4, rot);
                break;

            case STATE_CAPTURE_DRIVE:
                drive(0, 0.5, 0);
                if (now - captureStartTime > 2000) currentState = STATE_SEARCH_BALL;
                break;

            case STATE_SEARCH_APRILTAG:
                // Rotation until target tag is found
                drive(0, 0, 0.3);
                if (tagVisible) {
                    log("Base Tag " + TARGET_APRILTAG_ID + " found!");
                    currentState = STATE_ALIGN_TO_TAG;
                }
                break;

            case STATE_ALIGN_TO_TAG:
                if (!tagVisible) { currentState = STATE_SEARCH_APRILTAG; break; }
                
                // --- STEP 1: CALCULATE TARGET ROBOT HEADING ---
                // currentImuYaw: The robot's absolute heading in the field (from IMU)
                // tagYaw: The TAG's angle relative to the CAMERA (0 = parallel)
                // To be parallel to the tag's surface, the robot needs to turn by exactly tagYaw.
                var currentImuYaw = getYawFromQuaternion();
                var targetImuYaw = currentImuYaw + tagYaw;

                log("current phone yaw" + " " + currentImuYaw)
                log("tag yaw" + " " + tagYaw)

                // --- STEP 2: ROTATE TO MATCH TAG HEADING ---
                // We rotate the robot until its IMU heading matches the target heading.
                var yawError = targetImuYaw - currentImuYaw; // This simplifies back to tagYaw, but uses IMU for stability
                var rotOutput = (yawError / 45.0) * kp_rot;

                log("yaw error" + yawError)
                // --- STEP 3: CENTER ON TAG (STRAFE) ---
                // While rotating to the correct heading, we strafe to keep the tag centered (0.5).
                var centerError = tagX - CENTER_X;
                var strafeOutput = centerError * kp_strafe;

                // Drive: Strafe to center, No forward, Rotate to match IMU target
                drive(strafeOutput, 0, rotOutput);

                // Transition when both parallel (IMU confirms heading) and centered
                if (Math.abs(yawError) < YAW_TOLERANCE_DEG && Math.abs(centerError) < 0.05) {
                    log("Parallel & Centered! IMU Heading: " + currentImuYaw.toFixed(2));
                    currentState = STATE_DRIVE_TO_BASE;
                }
                break;

            case STATE_DRIVE_TO_BASE:
                if (!tagVisible) { drive(0, 0.2, 0); } // Dead reckoning fallback
                else {
                    var rot = calculateRotationPID(tagX - CENTER_X);
                    drive(0, 0.3, rot);
                }
                
                // Assuming "wall_detected" when tag width covers most of the camera
                if (tagVisible && tagWidth > 0.6) {
                    log("Arrived at Base Wall. Depositing...");
                    depositStartTime = now;
                    currentState = STATE_DEPOSIT_BALLS;
                }
                break;

            case STATE_DEPOSIT_BALLS:
                drive(0, 0, 0);
                lift(UP, 0.6);
                wait(3000);
                lift(DOWN, 0.6);
                log("Mission Complete.");
                currentState = STATE_DONE;
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
