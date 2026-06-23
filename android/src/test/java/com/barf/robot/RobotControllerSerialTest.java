package com.barf.robot;

import com.barf.serial.UsbSerialManager;

import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RED-phase tests for USB-serial wiring in RobotController.
 *
 * Bug 1: RobotController.sendCommand() uses UDP to 192.168.1.100:4210, completely
 * ignoring UsbSerialManager. All tests here will fail until:
 *   - setUsbSerial(UsbSerialManager) method is added to RobotController
 *   - computeMotorSpeeds(int x, int y, int r) is extracted as a package-private method
 *   - sendCommand() routes through UsbSerialManager when connected
 */
public class RobotControllerSerialTest {

    // ── Test 1: setUsbSerial method existence ───────────────────────────────────

    /**
     * RobotController must expose setUsbSerial(UsbSerialManager) so MainActivity
     * can inject the serial transport after creating the controller.
     * Currently the method does not exist → FAIL.
     */
    @Test
    public void robotController_hasSetUsbSerialMethod() {
        Method setUsbSerial = null;
        try {
            setUsbSerial = RobotController.class.getMethod("setUsbSerial", UsbSerialManager.class);
        } catch (NoSuchMethodException e) {
            // expected to fail at RED phase
        }
        assertNotNull(
                "RobotController must have a public setUsbSerial(UsbSerialManager) method",
                setUsbSerial);
    }

    // ── Tests 2-5: computeMotorSpeeds mecanum kinematics ────────────────────────

    /**
     * Helper that finds computeMotorSpeeds via reflection and invokes it.
     * Returns null if the method is absent (causing callers to fail explicitly).
     */
    private int[] invokeComputeMotorSpeeds(RobotController rc, int x, int y, int r) throws Exception {
        Method m = null;
        try {
            // package-private or public visibility both accepted
            m = RobotController.class.getDeclaredMethod("computeMotorSpeeds", int.class, int.class, int.class);
            m.setAccessible(true);
        } catch (NoSuchMethodException e) {
            return null;
        }
        return (int[]) m.invoke(rc, x, y, r);
    }

    /**
     * Forward (x=0, y=255, r=0): mecanum formula gives FL=FR=BL=BR=255.
     * computeMotorSpeeds does not exist yet → FAIL.
     */
    @Test
    public void computeMotorSpeeds_forward() throws Exception {
        RobotController rc = new RobotController();
        int[] speeds = invokeComputeMotorSpeeds(rc, 0, 255, 0);
        if (speeds == null) fail("computeMotorSpeeds method not found on RobotController");
        assertEquals("FL should be 255 for forward", 255, speeds[0]);
        assertEquals("FR should be 255 for forward", 255, speeds[1]);
        assertEquals("BL should be 255 for forward", 255, speeds[2]);
        assertEquals("BR should be 255 for forward", 255, speeds[3]);
    }

    /**
     * Strafe right (x=255, y=0, r=0): mecanum gives FL=255, FR=-255, BL=-255, BR=255.
     * computeMotorSpeeds does not exist yet → FAIL.
     */
    @Test
    public void computeMotorSpeeds_strafeRight() throws Exception {
        RobotController rc = new RobotController();
        int[] speeds = invokeComputeMotorSpeeds(rc, 255, 0, 0);
        if (speeds == null) fail("computeMotorSpeeds method not found on RobotController");
        assertEquals("FL should be  255 for strafe-right",  255, speeds[0]);
        assertEquals("FR should be -255 for strafe-right", -255, speeds[1]);
        assertEquals("BL should be -255 for strafe-right", -255, speeds[2]);
        assertEquals("BR should be  255 for strafe-right",  255, speeds[3]);
    }

    /**
     * Rotate right (x=0, y=0, r=255): mecanum gives FL=255, FR=-255, BL=255, BR=-255.
     * computeMotorSpeeds does not exist yet → FAIL.
     */
    @Test
    public void computeMotorSpeeds_rotateRight() throws Exception {
        RobotController rc = new RobotController();
        int[] speeds = invokeComputeMotorSpeeds(rc, 0, 0, 255);
        if (speeds == null) fail("computeMotorSpeeds method not found on RobotController");
        assertEquals("FL should be  255 for rotate-right",  255, speeds[0]);
        assertEquals("FR should be -255 for rotate-right", -255, speeds[1]);
        assertEquals("BL should be  255 for rotate-right",  255, speeds[2]);
        assertEquals("BR should be -255 for rotate-right", -255, speeds[3]);
    }

    /**
     * Overflow clamping (x=255, y=255, r=255):
     *   FL = clamp(y+x+r) = clamp(765)  =  255
     *   FR = clamp(y-x-r) = clamp(-255) = -255
     *   BL = clamp(y-x+r) = clamp(255)  =  255   ← wait, y-x+r = 255
     *   BR = clamp(y+x-r) = clamp(255)  =  255
     * Recalculated per formula FL=y+x+r, FR=y-x-r, BL=y-x+r, BR=y+x-r:
     *   FL = 255+255+255 = 765 → clamp → 255
     *   FR = 255-255-255 = -255 → clamp → -255
     *   BL = 255-255+255 = 255 → clamp →  255  (no actual overflow here)
     *   BR = 255+255-255 = 255 → clamp →  255  (no actual overflow here)
     * computeMotorSpeeds does not exist yet → FAIL.
     */
    @Test
    public void computeMotorSpeeds_clampsOverflow() throws Exception {
        RobotController rc = new RobotController();
        int[] speeds = invokeComputeMotorSpeeds(rc, 255, 255, 255);
        if (speeds == null) fail("computeMotorSpeeds method not found on RobotController");
        assertEquals("FL should clamp to  255",  255, speeds[0]);
        assertEquals("FR should clamp to -255", -255, speeds[1]);
        assertEquals("BL should be  255",        255, speeds[2]);
        assertEquals("BR should be  255",        255, speeds[3]);
    }

    // ── Tests 6-7: serial routing ────────────────────────────────────────────────

    /**
     * When a connected UsbSerialManager is injected, move("forward", 1.0f) must
     * write a JSON motor command containing the "m" key to serial rather than UDP.
     * setUsbSerial does not exist yet → FAIL.
     */
    @Test
    public void robotController_whenSerialConnected_writesSerialCommand() throws Exception {
        UsbSerialManager mockSerial = Mockito.mock(UsbSerialManager.class);
        when(mockSerial.isConnected()).thenReturn(true);

        RobotController rc = new RobotController();

        // Inject via setUsbSerial — will throw NoSuchMethodException until implemented
        Method setUsbSerial;
        try {
            setUsbSerial = RobotController.class.getMethod("setUsbSerial", UsbSerialManager.class);
        } catch (NoSuchMethodException e) {
            fail("RobotController.setUsbSerial(UsbSerialManager) must exist");
            return;
        }
        setUsbSerial.invoke(rc, mockSerial);

        rc.move("forward", 1.0f);

        // Give the send thread a moment to execute
        Thread.sleep(200);

        verify(mockSerial, Mockito.atLeastOnce()).write(anyString());

        // Capture and verify the written string contains the "m" protocol key
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockSerial, Mockito.atLeastOnce()).write(captor.capture());
        boolean containsMotorKey = captor.getAllValues().stream().anyMatch(s -> s.contains("\"m\""));
        assertEquals("Written serial command must contain the motor key \"m\"", true, containsMotorKey);
    }

    /**
     * When UsbSerialManager reports isConnected()=false, move() must NOT write
     * to serial (it may fall back to UDP, but serial.write must not be called).
     * setUsbSerial does not exist yet → FAIL.
     */
    @Test
    public void robotController_whenSerialDisconnected_doesNotWriteSerial() throws Exception {
        UsbSerialManager mockSerial = Mockito.mock(UsbSerialManager.class);
        when(mockSerial.isConnected()).thenReturn(false);

        RobotController rc = new RobotController();

        Method setUsbSerial;
        try {
            setUsbSerial = RobotController.class.getMethod("setUsbSerial", UsbSerialManager.class);
        } catch (NoSuchMethodException e) {
            fail("RobotController.setUsbSerial(UsbSerialManager) must exist");
            return;
        }
        setUsbSerial.invoke(rc, mockSerial);

        rc.move("forward", 1.0f);

        Thread.sleep(200);

        verify(mockSerial, never()).write(anyString());
    }
}
