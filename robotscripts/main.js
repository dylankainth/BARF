    // http://10.104.125.78:8080/
    // 10.104.125.167
    const pingponglabel = 32

    // onDetection(function(dets) {
    //     if (!dets || dets.length === 0) {
    //         console.log("No detections");
    //         return;
    //     }
    //     const yolodets = dets.get("yolo");
    //     for (var i = 0; i < Math.trunc(yolodets.size()); i++) {
    //         var d = yolodets.get(i);
    //         const detObj = {
    //                 label: Math.trunc(d.get("label")),
    //                 x: d.get("x"),
    //                 y: d.get("y"),
    //                 w: d.get("w"),
    //                 h: d.get("h"),
    //                 score: d.get("score")
    //         };
    //         if (detObj.label == pingponglabel){
    //             console.log("DET: " + detObj.label +
    //                         " x:" + detObj.x + " y:" + detObj.y +
    //                         " w:" + detObj.w + " h:" + detObj.h +
    //                         " score:" + detObj.score);
    //         }
    //     }
    // });

    // Heartbeat loop to keep the script running (will exit if script is stopped)
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
            
            // Drive forward at 50% speed while curving right at 30% speed
            drive(0, 0.2, 0.3);
            wait(500);
            // drive(0, 0.2, -0.3);
        } catch (e) {
            console.log("Script interrupted or stopped: " + e);
            break;
        }
    }