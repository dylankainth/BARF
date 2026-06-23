package com.barf;

import android.content.Intent;

import com.barf.serial.UsbSerialManager;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * RED-phase tests for USB-serial wiring in MainActivity.
 *
 * Bug 2: UsbSerialManager is a local variable inside MainActivity.startServer(),
 * never stored as a field, and MainActivity has no onNewIntent(Intent) to respond
 * to USB_DEVICE_ATTACHED intents. Both tests fail until:
 *   - UsbSerialManager is promoted to an instance field named usbSerialManager
 *   - onNewIntent(Intent) is added to MainActivity
 */
public class MainActivityUsbTest {

    /**
     * MainActivity must store the UsbSerialManager as an instance field named
     * "usbSerialManager" so it can be accessed for USB_DEVICE_ATTACHED handling
     * and lifecycle management (connect/disconnect on resume/pause).
     * Currently it is a local variable inside startServer() → FAIL.
     */
    @Test
    public void mainActivity_hasUsbSerialManagerField() {
        Field field = null;
        try {
            field = MainActivity.class.getDeclaredField("usbSerialManager");
        } catch (NoSuchFieldException e) {
            // expected at RED phase
        }

        assertNotNull(
                "MainActivity must have an instance field named 'usbSerialManager' "
                        + "of type UsbSerialManager (currently a local variable in startServer())",
                field);

        if (field != null) {
            assertEquals(
                    "usbSerialManager field must be of type UsbSerialManager",
                    UsbSerialManager.class,
                    field.getType());
        }
    }

    /**
     * MainActivity must override onNewIntent(Intent) to handle
     * android.hardware.usb.action.USB_DEVICE_ATTACHED intents sent by the OS
     * when the ESP32 is plugged in after the app is already running.
     * Currently absent → FAIL.
     */
    @Test
    public void mainActivity_hasOnNewIntentMethod() {
        Method onNewIntent = null;
        try {
            onNewIntent = MainActivity.class.getDeclaredMethod("onNewIntent", Intent.class);
        } catch (NoSuchMethodException e) {
            // expected at RED phase
        }

        assertNotNull(
                "MainActivity must override onNewIntent(Intent) to handle "
                        + "USB_DEVICE_ATTACHED intents from the Android OS",
                onNewIntent);
    }
}
