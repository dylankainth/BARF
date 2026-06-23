package com.barf.pairing;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * RED-phase tests for Bug 2: {@code cameraClosedForPairing} flag corrupted by
 * {@code Activity.onStop()}.
 *
 * In {@code MainActivity}, the flag is set to {@code true} before launching
 * {@code PairingActivity}, but {@code onStop()} unconditionally resets it to
 * {@code false} while {@code PairingActivity} is still on screen. This causes
 * {@code onResume()} to re-open the camera before pairing completes.
 *
 * The fix is to extract the flag and its mutation logic into a dedicated
 * {@code CameraHandoffState} helper class in the {@code com.barf.pairing} package,
 * so the lifecycle transition and the pairing-complete callback are the only two
 * places that touch the flag.
 *
 * All tests here fail until {@code CameraHandoffState} is created.
 */
public class CameraHandoffFlagTest {

    private static final String CLASS_NAME = "com.barf.pairing.CameraHandoffState";

    // ── class existence ────────────────────────────────────────────────────────

    /**
     * {@code CameraHandoffState} class must exist in {@code com.barf.pairing}.
     */
    @Test
    public void cameraHandoffState_classExists() throws Exception {
        Class<?> clazz = loadCameraHandoffState();
        assertNotNull("com.barf.pairing.CameraHandoffState must exist.", clazz);
    }

    /**
     * {@code CameraHandoffState} must have a no-arg constructor.
     */
    @Test
    public void cameraHandoffState_hasNoArgConstructor() throws Exception {
        Class<?> clazz = loadCameraHandoffState();
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        assertNotNull("CameraHandoffState must have a no-arg constructor.", ctor);
    }

    /**
     * {@code CameraHandoffState} must expose {@code isCameraClosedForPairing()}.
     */
    @Test
    public void cameraHandoffState_hasIsCameraClosedForPairingMethod() throws Exception {
        Class<?> clazz = loadCameraHandoffState();
        Method m = findMethod(clazz, "isCameraClosedForPairing");
        assertNotNull("CameraHandoffState must declare 'boolean isCameraClosedForPairing()'.", m);
    }

    /**
     * {@code CameraHandoffState} must expose {@code setCameraClosedForPairing(boolean)}.
     */
    @Test
    public void cameraHandoffState_hasSetCameraClosedForPairingMethod() throws Exception {
        Class<?> clazz = loadCameraHandoffState();
        Method m = findMethodWithParam(clazz, "setCameraClosedForPairing", boolean.class);
        assertNotNull("CameraHandoffState must declare 'void setCameraClosedForPairing(boolean)'.", m);
    }

    /**
     * {@code CameraHandoffState} must expose {@code onLifecycleStop()}.
     */
    @Test
    public void cameraHandoffState_hasOnLifecycleStopMethod() throws Exception {
        Class<?> clazz = loadCameraHandoffState();
        Method m = findMethod(clazz, "onLifecycleStop");
        assertNotNull("CameraHandoffState must declare 'void onLifecycleStop()'.", m);
    }

    /**
     * {@code CameraHandoffState} must expose {@code onPairingComplete()}.
     */
    @Test
    public void cameraHandoffState_hasOnPairingCompleteMethod() throws Exception {
        Class<?> clazz = loadCameraHandoffState();
        Method m = findMethod(clazz, "onPairingComplete");
        assertNotNull("CameraHandoffState must declare 'void onPairingComplete()'.", m);
    }

    // ── initial state ──────────────────────────────────────────────────────────

    /**
     * The flag starts {@code false} — camera is not held closed for pairing until
     * the user taps the pair button.
     */
    @Test
    public void initialState_cameraClosedForPairingIsFalse() throws Exception {
        Object state = newState();
        boolean value = (boolean) getIsClosed(state);
        assertFalse(
                "Flag must be false on construction — no pairing in progress yet.",
                value);
    }

    // ── pair button flow ───────────────────────────────────────────────────────

    /**
     * Calling {@code setCameraClosedForPairing(true)} (simulating the pair-button click)
     * must raise the flag to {@code true}.
     */
    @Test
    public void setCameraClosedForPairing_setsFlag() throws Exception {
        Object state = newState();
        setClosedForPairing(state, true);
        boolean value = (boolean) getIsClosed(state);
        assertTrue(
                "Flag must be true after setCameraClosedForPairing(true).",
                value);
    }

    // ── Activity lifecycle onStop does NOT reset the flag ─────────────────────

    /**
     * Calling {@code onLifecycleStop()} while the flag is {@code true} (i.e., pairing
     * is in progress) must NOT reset the flag to {@code false}.
     *
     * This is the core regression: in the broken code {@code Activity.onStop()} resets
     * {@code cameraClosedForPairing = false} unconditionally.
     */
    @Test
    public void onLifecycleStop_whenCameraClosedForPairing_doesNotResetFlag() throws Exception {
        Object state = newState();
        setClosedForPairing(state, true);

        onLifecycleStop(state);

        boolean value = (boolean) getIsClosed(state);
        assertTrue(
                "onLifecycleStop() must NOT reset cameraClosedForPairing while pairing is in progress. "
                        + "The flag was true before onStop; it must remain true after.",
                value);
    }

    /**
     * Calling {@code onLifecycleStop()} when the flag is already {@code false} must
     * leave it {@code false} — no unintended side effects.
     */
    @Test
    public void onLifecycleStop_whenFlagAlreadyFalse_remainsFalse() throws Exception {
        Object state = newState();

        onLifecycleStop(state);

        boolean value = (boolean) getIsClosed(state);
        assertFalse(
                "onLifecycleStop() must not change the flag when it was already false.",
                value);
    }

    // ── onPairingComplete resets the flag ──────────────────────────────────────

    /**
     * {@code onPairingComplete()} is the ONLY call that should reset the flag to
     * {@code false} — corresponding to {@code onActivityResult(REQUEST_PAIR, ...)}.
     */
    @Test
    public void onPairingComplete_resetsFlagToFalse() throws Exception {
        Object state = newState();
        setClosedForPairing(state, true);

        onPairingComplete(state);

        boolean value = (boolean) getIsClosed(state);
        assertFalse(
                "onPairingComplete() must reset cameraClosedForPairing to false "
                        + "so the camera re-opens after pairing finishes.",
                value);
    }

    /**
     * Calling {@code onPairingComplete()} when the flag is already {@code false}
     * must be a no-op (no exception, flag stays false).
     */
    @Test
    public void onPairingComplete_whenFlagAlreadyFalse_remainsFalse() throws Exception {
        Object state = newState();

        onPairingComplete(state);

        boolean value = (boolean) getIsClosed(state);
        assertFalse(
                "onPairingComplete() on an already-false flag must leave it false.",
                value);
    }

    // ── round-trip: set → stop → complete ─────────────────────────────────────

    /**
     * Full lifecycle: flag set → Activity.onStop() fires (flag must survive) →
     * pairing completes (flag finally resets).
     */
    @Test
    public void fullPairingLifecycle_flagSurvivesOnStopAndResetsOnComplete() throws Exception {
        Object state = newState();

        setClosedForPairing(state, true);
        assertTrue("Flag must be true after pair button tap.",
                (boolean) getIsClosed(state));

        onLifecycleStop(state);
        assertTrue("Flag must still be true while PairingActivity is on screen.",
                (boolean) getIsClosed(state));

        onPairingComplete(state);
        assertFalse("Flag must be false after pairing is complete.",
                (boolean) getIsClosed(state));
    }

    // ── reflection helpers ────────────────────────────────────────────────────

    private static Class<?> loadCameraHandoffState() {
        try {
            return Class.forName(CLASS_NAME);
        } catch (ClassNotFoundException e) {
            fail("Class '" + CLASS_NAME + "' does not exist. "
                    + "Create it as part of the Bug 2 fix.");
            return null; // unreachable
        }
    }

    private static Object newState() throws Exception {
        Class<?> clazz = loadCameraHandoffState();
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static Object getIsClosed(Object state) throws Exception {
        Method m = findMethod(state.getClass(), "isCameraClosedForPairing");
        assertNotNull("isCameraClosedForPairing() must exist", m);
        m.setAccessible(true);
        return m.invoke(state);
    }

    private static void setClosedForPairing(Object state, boolean value) throws Exception {
        Method m = findMethodWithParam(state.getClass(), "setCameraClosedForPairing", boolean.class);
        assertNotNull("setCameraClosedForPairing(boolean) must exist", m);
        m.setAccessible(true);
        m.invoke(state, value);
    }

    private static void onLifecycleStop(Object state) throws Exception {
        Method m = findMethod(state.getClass(), "onLifecycleStop");
        assertNotNull("onLifecycleStop() must exist", m);
        m.setAccessible(true);
        m.invoke(state);
    }

    private static void onPairingComplete(Object state) throws Exception {
        Method m = findMethod(state.getClass(), "onPairingComplete");
        assertNotNull("onPairingComplete() must exist", m);
        m.setAccessible(true);
        m.invoke(state);
    }

    private static Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (name.equals(m.getName()) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    private static Method findMethodWithParam(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
