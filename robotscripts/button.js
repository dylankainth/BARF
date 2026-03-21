onButton(function (state) {
    if (state === "pressed") {
        log("Button pressed");
    } else if (state === "released") {
        log("Button released");
    }
});

while (true) {
    try {
        wait(1000);
    } catch (e) {
        console.log("Script interrupted or stopped: " + e);
        break;
    }
}