package com.barf.robot;

import android.util.Log;

import com.barf.serial.UsbSerialManager;
import com.barf.serial.SerialProtocol;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Controls robot movement. Manages motor state and UDP communication with the ESP32.
 * In competition mode, the WASM vision script drives this; in dev mode, the desktop app does.
 */
public class RobotController {
    private static final String TAG = "RobotController";
    private static final int ROBOT_UDP_PORT = 4210;

    private volatile boolean isMoving = false;
    private volatile String lastCommand = "none";
    private volatile int robotX = 0;
    private volatile int robotY = 0;
    private volatile int robotR = 0;
    private volatile int robotE = 0;

    private DatagramSocket udpSocket;
    private String robotIp = "192.168.1.100";
    private UsbSerialManager usbSerial;

    public RobotController() {
        initializeUdpSocket();
    }

    private void initializeUdpSocket() {
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
            udpSocket = new DatagramSocket();
            Log.i(TAG, "UDP socket initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize UDP socket: " + e.getMessage());
        }
    }

    public void setUsbSerial(UsbSerialManager usbSerial) {
        this.usbSerial = usbSerial;
    }

    public void setRobotIp(String ip) {
        this.robotIp = ip;
    }

    public String getRobotIp() {
        return robotIp;
    }

    public void move(String direction, float speed) {
        Log.i(TAG, "Robot move: " + direction + " speed: " + speed);
        isMoving = true;
        lastCommand = "move:" + direction + ":" + speed;

        int motorSpeed = (int) (speed * 255);
        int x = 0, y = 0;

        switch (direction.toLowerCase()) {
            case "forward":
                y = motorSpeed;
                break;
            case "backward":
                y = -motorSpeed;
                break;
            case "left":
                x = -motorSpeed;
                break;
            case "right":
                x = motorSpeed;
                break;
        }

        sendCommand(x, y, 0, 0);
    }

    public void rotate(String direction, float speed) {
        Log.i(TAG, "Robot rotate: " + direction + " speed: " + speed);
        isMoving = true;
        lastCommand = "rotate:" + direction + ":" + speed;

        int motorSpeed = (int) (speed * 255);
        int r = 0;

        switch (direction.toLowerCase()) {
            case "left":
                r = -motorSpeed;
                break;
            case "right":
                r = motorSpeed;
                break;
        }

        sendCommand(0, 0, r, 0);
    }

    public void stop() {
        Log.i(TAG, "Robot stop");
        isMoving = false;
        lastCommand = "stop";
        sendCommand(0, 0, 0, 0);
    }

    public boolean isMoving() {
        return isMoving;
    }

    public String getLastCommand() {
        return lastCommand;
    }

    public int[] getMotorValues() {
        return new int[]{robotX, robotY, robotR, robotE};
    }

    public RobotStatus getStatus() {
        RobotStatus status = new RobotStatus();
        status.isMoving = isMoving;
        status.lastCommand = lastCommand;
        status.timestamp = System.currentTimeMillis();
        return status;
    }

    int[] computeMotorSpeeds(int x, int y, int r) {
        int fl = Math.max(-255, Math.min(255, y + x + r));
        int fr = Math.max(-255, Math.min(255, y - x - r));
        int bl = Math.max(-255, Math.min(255, y - x + r));
        int br = Math.max(-255, Math.min(255, y + x - r));
        return new int[]{fl, fr, bl, br};
    }

    private void sendCommand(int x, int y, int r, int e) {
        robotX = x;
        robotY = y;
        robotR = r;
        robotE = e;

        if (usbSerial != null && usbSerial.isConnected()) {
            // Primary path: USB-serial to ESP32
            int[] speeds = computeMotorSpeeds(x, y, r);
            String msg = SerialProtocol.motorCommand(speeds);
            new Thread(() -> usbSerial.write(msg)).start();
            Log.d(TAG, "Sent serial: " + msg.trim());
        } else {
            // Fallback: UDP (dev mode without USB cable)
            if (udpSocket == null || udpSocket.isClosed()) {
                Log.w(TAG, "UDP socket not initialized");
                return;
            }
            new Thread(() -> {
                try {
                    String command = x + "," + y + "," + r + "," + e;
                    byte[] commandBytes = command.getBytes();
                    InetAddress address = InetAddress.getByName(robotIp);
                    DatagramPacket packet = new DatagramPacket(commandBytes, commandBytes.length, address, ROBOT_UDP_PORT);
                    udpSocket.send(packet);
                    Log.d(TAG, "Sent UDP to " + robotIp + ":" + ROBOT_UDP_PORT + " -> " + command);
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to send UDP command: " + ex.getMessage());
                }
            }).start();
        }
    }

    public void shutdown() {
        stop();
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            Log.i(TAG, "UDP socket closed");
        }
    }

    public static class RobotStatus {
        public boolean isMoving;
        public String lastCommand;
        public int cameraFacing;
        public long timestamp;
    }
}
