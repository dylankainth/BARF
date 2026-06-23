package com.barf.serial;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.barf.AppLog;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages USB-serial communication with the ESP32 over USB-OTG.
 * Uses usb-serial-for-android which handles CP2102, CH340, FTDI, and CDC ACM
 * correctly — no manual control-transfer nonsense.
 */
public class UsbSerialManager {
    private static final String TAG = "UsbSerialManager";
    private static final String ACTION_USB_PERMISSION = "com.barf.serial.USB_PERMISSION";
    private static final int BAUD_RATE = 115200;

    private final Context context;
    private final UsbManager usbManager;

    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    private UsbDevice currentDevice;
    private UsbSerialListener listener;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final StringBuilder lineBuffer = new StringBuilder();

    public interface UsbSerialListener {
        void onConnected();
        void onDisconnected();
        void onMessageReceived(String line);
        void onHeartbeatTimeout();
    }

    public UsbSerialManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(UsbSerialListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected.get();
    }

    /** Scan for any known serial device and connect. */
    public boolean connect() {
        if (connected.get()) return true;
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        AppLog.d(TAG, "USB scan — " + drivers.size() + " serial driver(s) found");
        for (UsbSerialDriver d : drivers) {
            UsbDevice dev = d.getDevice();
            AppLog.d(TAG, String.format(Locale.US, "  driver=%s VID:0x%04X PID:0x%04X  %s",
                    d.getClass().getSimpleName(), dev.getVendorId(), dev.getProductId(),
                    dev.getProductName()));
        }
        if (drivers.isEmpty()) {
            // Also dump raw device list so we can see what's actually attached
            for (UsbDevice dev : usbManager.getDeviceList().values()) {
                AppLog.w(TAG, String.format(Locale.US, "  unrecognised device: VID:0x%04X PID:0x%04X  %s",
                        dev.getVendorId(), dev.getProductId(), dev.getProductName()));
            }
            AppLog.w(TAG, "No recognised serial device found");
            return false;
        }
        return connectToDevice(drivers.get(0).getDevice());
    }

    /** Retry — tries previous device first, then scans. Safe to call repeatedly. */
    public boolean reconnect() {
        if (connected.get()) return true;
        if (currentDevice != null) return connectToDevice(currentDevice);
        return connect();
    }

    /** Connect to a specific device (e.g. received from USB_DEVICE_ATTACHED intent). */
    public boolean connectToDevice(UsbDevice device) {
        if (connected.get()) return true;
        currentDevice = device;
        if (!usbManager.hasPermission(device)) {
            AppLog.w(TAG, "No permission for " + device.getProductName() + " — requesting (approve the dialog!)");
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            context.registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
            usbManager.requestPermission(device, pi);
            return false;
        }
        return openPort(device);
    }

    private boolean openPort(UsbDevice device) {
        // Find the driver for this specific device
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        UsbSerialDriver driver = null;
        for (UsbSerialDriver d : drivers) {
            if (d.getDevice().getDeviceId() == device.getDeviceId()) {
                driver = d;
                break;
            }
        }
        if (driver == null) {
            AppLog.e(TAG, "No driver for device VID:0x" + Integer.toHexString(device.getVendorId())
                    + " — not a recognised serial chip");
            return false;
        }

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            AppLog.e(TAG, "openDevice returned null — permission missing or device gone");
            return false;
        }

        try {
            port = driver.getPorts().get(0);
            port.open(conn);
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try { port.setDTR(true); } catch (Exception ignored) {}
            try { port.setRTS(true); } catch (Exception ignored) {}
        } catch (Exception e) {
            AppLog.e(TAG, "Port open/configure failed: " + e.getMessage());
            try { port.close(); } catch (Exception ignored) {}
            try { conn.close(); } catch (Exception ignored) {}
            port = null;
            return false;
        }

        // Async reader — library handles the read loop
        ioManager = new SerialInputOutputManager(port, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                handleIncoming(data);
            }

            @Override
            public void onRunError(Exception e) {
                AppLog.e(TAG, "IO error: " + e.getMessage());
                handleDisconnect();
            }
        });
        ioManager.start();

        connected.set(true);
        AppLog.i(TAG, "Connected: " + getDeviceInfo());
        if (listener != null) listener.onConnected();
        return true;
    }

    private synchronized void handleIncoming(byte[] data) {
        try {
            lineBuffer.append(new String(data, "UTF-8"));
            int idx;
            while ((idx = lineBuffer.indexOf("\n")) >= 0) {
                String line = lineBuffer.substring(0, idx).trim();
                lineBuffer.delete(0, idx + 1);
                if (!line.isEmpty()) {
                    AppLog.d(TAG, "RX: " + line);
                    if (listener != null) listener.onMessageReceived(line);
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "RX decode: " + e.getMessage());
        }
    }

    private void handleDisconnect() {
        if (!connected.getAndSet(false)) return; // already disconnected
        if (ioManager != null) { ioManager.stop(); ioManager = null; }
        try { if (port != null) port.close(); } catch (Exception ignored) {}
        port = null;
        AppLog.i(TAG, "Disconnected");
        if (listener != null) listener.onDisconnected();
    }

    public void sendMotorCommand(int[] speeds) {
        write(SerialProtocol.motorCommand(speeds));
    }

    public synchronized void write(String data) {
        if (!connected.get() || port == null) {
            AppLog.w(TAG, "Write skipped — not connected");
            return;
        }
        try {
            port.write(data.getBytes("UTF-8"), 200);
        } catch (IOException e) {
            AppLog.e(TAG, "Write error: " + e.getMessage());
            handleDisconnect();
        } catch (Exception e) {
            AppLog.e(TAG, "Write error: " + e.getMessage());
        }
    }

    public String pollMessage() {
        return null; // messages are pushed via listener.onMessageReceived
    }

    public void disconnect() {
        handleDisconnect();
    }

    /** Human-readable description of the connected device and detected chip driver. */
    public String getDeviceInfo() {
        if (currentDevice == null) return "no device";
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        String driverName = "unknown";
        for (UsbSerialDriver d : drivers) {
            if (d.getDevice().getDeviceId() == currentDevice.getDeviceId()) {
                driverName = d.getClass().getSimpleName().replace("SerialDriver", "");
                break;
            }
        }
        return String.format(Locale.US, "VID:0x%04X PID:0x%04X  %s  %s",
                currentDevice.getVendorId(), currentDevice.getProductId(),
                driverName,
                currentDevice.getProductName() != null ? currentDevice.getProductName() : "");
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                AppLog.i(TAG, "USB permission granted — opening port");
                openPort(device);
            } else {
                AppLog.w(TAG, "USB permission denied by user");
            }
            try { context.unregisterReceiver(this); } catch (Exception ignored) {}
        }
    };
}
