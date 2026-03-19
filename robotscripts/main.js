const pingponglabel = 32

onDetection(function(dets) {
    if (!dets) {
        console.log("No detections");
        return;
    }
    const apriltags = dets.get("apriltags");
    log(apriltags) // example: [{id=42.0, cx=0.3064, cy=0.3282, corners=[[0.1624, 0.5498], [0.4781, 0.5246], [0.4602, 0.0916], [0.1203, 0.1153]]}]
    
    // const yolodets = dets.get("yolo");
    // for (var i = 0; i < Math.trunc(yolodets.size()); i++) {
    //     var d = yolodets.get(i);
    //     const detObj = {
    //             label: Math.trunc(d.get("label")),
    //             x: d.get("x"),
    //             y: d.get("y"),
    //             w: d.get("w"),
    //             h: d.get("h"),
    //             score: d.get("score")
    //     };
    //     if (detObj.label == pingponglabel){
    //         console.log("DET: " + detObj.label +
    //                     " x:" + detObj.x + " y:" + detObj.y +
    //                     " w:" + detObj.w + " h:" + detObj.h +
    //                     " score:" + detObj.score);
    //     }
    // }
});

// Heartbeat loop to keep the script running (will exit if script is stopped)
// stopAprilTag();
// startYolo();
stopYolo();
startAprilTag();
while (true) {
    try {
        // move(FORWARD, 0.5);
        // move(BACKWARD, 0.5);
        // move(LEFT,0.2);
        // move(RIGHT,0.2);
        // rotate(RIGHT,0.2);
        // rotate(LEFT,0.2);
        // lift(UP,0.3);
        // lift(DOWN,0.3);
        
        // drive(sideways, forward, rotation)
        // Drive forward at 50% speed while curving right at 30% speed
        // drive(0, 0.2, 0.3);
        // wait(500);
        // drive(0, 0.2, -0.3);
    } catch (e) {
        console.log("Script interrupted or stopped: " + e);
        break;
    }
}