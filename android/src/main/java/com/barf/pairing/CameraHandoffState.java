package com.barf.pairing;

/**
 * Tracks whether the camera was intentionally closed to hand off to PairingActivity.
 * Extracted from MainActivity to make the lifecycle flag testable and prevent
 * Activity.onStop() from corrupting the flag mid-pairing-flow.
 */
public class CameraHandoffState {
    private boolean cameraClosedForPairing = false;

    public boolean isCameraClosedForPairing() {
        return cameraClosedForPairing;
    }

    public void setCameraClosedForPairing(boolean value) {
        cameraClosedForPairing = value;
    }

    /** Called when Activity.onStop() fires — must NOT reset the flag. */
    public void onLifecycleStop() {
        // Intentionally does nothing. The flag is only cleared by onPairingComplete().
    }

    /** Called from onActivityResult() after pairing attempt finishes. */
    public void onPairingComplete() {
        cameraClosedForPairing = false;
    }
}
