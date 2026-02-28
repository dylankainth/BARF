// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.tencent.yolo11ncnn;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.ActivityInfo;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

// Removed complex robot imports - using simple server now

public class MainActivity extends Activity implements SurfaceHolder.Callback, SimpleHttpServer.RobotControlCallback {
    /**
     * Adjust SurfaceView aspect ratio to match camera preview size
     */
    private void adjustSurfaceViewAspectRatio() {
        // Example: 16:9 aspect ratio
        int previewWidth = 1280;
        int previewHeight = 720;
        // You may want to get actual camera preview size from yolo11ncnn
        float aspectRatio = (float) previewWidth / previewHeight;
        int viewWidth = cameraView.getWidth();
        int viewHeight = cameraView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;
        int newWidth = viewWidth;
        int newHeight = (int) (viewWidth / aspectRatio);
        if (newHeight > viewHeight) {
            newHeight = viewHeight;
            newWidth = (int) (viewHeight * aspectRatio);
        }
        cameraView.getLayoutParams().width = newWidth;
        cameraView.getLayoutParams().height = newHeight;
        cameraView.requestLayout();
    }

    /**
     * Fix bitmap orientation if camera facing front or device rotated
     */
    private Bitmap fixBitmapOrientation(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        // If already landscape, no rotation needed
        if (w >= h) return src;

        // Rotate portrait frames to landscape. Back camera rotate 90, front rotate 270.
        int rotationDegrees = (facing == 1) ? 270 : 90;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, w, h, matrix, true);
        return rotated;
    }
    public static final int REQUEST_CAMERA = 100;
    public static final int REQUEST_NOTIFICATION = 101;

    private YOLO11Ncnn yolo11ncnn = new YOLO11Ncnn();
    private boolean serverInitialized = false;
    private int facing = 1;
    
    // Robot control state
    private volatile boolean isMoving = false;
    private volatile String lastCommand = "none";
    
    // UDP robot communication
    private DatagramSocket udpSocket;
    private static final int ROBOT_UDP_PORT = 4210;
    
    // Robot movement state (X,Y,R,E format for ESP32)
    private volatile int robotX = 0;    // Strafe: -255 to 255
    private volatile int robotY = 0;    // Forward/Back: -255 to 255  
    private volatile int robotR = 0;    // Rotation: -255 to 255
    private volatile int robotE = 0;    // Elevator: -255 to 255

    private Spinner spinnerTask;
    private Spinner spinnerModel;
    private Spinner spinnerCPUGPU;
    private int current_task = 0;
    private int current_model = 0;
    private int current_cpugpu = 0;

    private SurfaceView cameraView;
    
    // Simple HTTP/WebSocket server for testing
    private SimpleHttpServer simpleServer;
    // Static reference used by native code to forward detections into Java
    private static SimpleHttpServer sSimpleServerStatic = null;
    private TextView serverStatusText;
    
    // Video streaming
    private Thread videoStreamThread;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide the title/action bar
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        // Force landscape orientation so camera feed is landscape by default
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cameraView = (SurfaceView) findViewById(R.id.cameraview);

        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(this);

        // Fix SurfaceView aspect ratio to match camera
        cameraView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                adjustSurfaceViewAspectRatio();
            }
        });

        Button buttonSwitchCamera = (Button) findViewById(R.id.buttonSwitchCamera);
        buttonSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {

                int new_facing = 1 - facing;

                yolo11ncnn.closeCamera();

                yolo11ncnn.openCamera(new_facing);

                facing = new_facing;
                
                // Test WebSocket broadcast when switching camera
                broadcastTestMessage("Camera switched to " + (new_facing == 0 ? "back" : "front"));
            }
        });

        spinnerTask = (Spinner) findViewById(R.id.spinnerTask);
        spinnerTask.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_task)
                {
                    current_task = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

        spinnerModel = (Spinner) findViewById(R.id.spinnerModel);
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_model)
                {
                    current_model = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

        spinnerCPUGPU = (Spinner) findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_cpugpu)
                {
                    current_cpugpu = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

        reload();
        
        // Start simple HTTP/WebSocket server
        startSimpleServer();
        
        // Initialize UDP socket for robot communication
        initializeUdpSocket();
    }
    
    /**
     * Start the simple HTTP/WebSocket server for basic testing.
     */
    private void startSimpleServer() {
        if (serverInitialized) return;
        serverInitialized = true;
        
        try {
            simpleServer = new SimpleHttpServer(this, 8080);
            // keep a static reference so native code can forward detections
            sSimpleServerStatic = simpleServer;
            // register this Activity with native YOLO code so native can callback safely
            try {
                yolo11ncnn.registerActivity(this);
            } catch (Exception e) {
                Log.w("MainActivity", "Failed to register activity with native layer: " + e.getMessage());
            }
            simpleServer.setRobotControlCallback(this);  // Set callback for robot commands
            simpleServer.startServer();
            
            // Start video streaming
            startVideoStreaming();
            
            String msg = "Simple server started on port 8080";
            Log.i("MainActivity", msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            
            if (serverStatusText != null) {
                serverStatusText.setText(msg);
            }
            
        } catch (Exception e) {
            String error = "Failed to start server: " + e.getMessage();
            Log.e("MainActivity", error);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Called from native code via JNI to push detection JSON into scripts.
     */
    public static void pushDetectionsToScripts(String json) {
        if (sSimpleServerStatic != null) {
            try {
                sSimpleServerStatic.pushDetections(json);
            } catch (Exception e) {
                Log.w("MainActivity", "pushDetectionsToScripts failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Start the video streaming thread that generates frames.
     */
    private void startVideoStreaming() {
        if (videoStreamThread != null && videoStreamThread.isAlive()) {
            return;
        }
        
        videoStreamThread = new Thread(() -> {
            Log.i("MainActivity", "Video streaming thread started");
            long frameCounter = 0;
            long lastLogTime = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicInteger pendingCopies = new java.util.concurrent.atomic.AtomicInteger(0);
            
            while (Thread.currentThread().isAlive() && simpleServer != null) {
                try {
                    // Capture real frame from SurfaceView using PixelCopy with backpressure
                    if (cameraView != null && cameraView.getHolder().getSurface().isValid()) {
                        int width = cameraView.getWidth();
                        int height = cameraView.getHeight();
                        
                        if (width > 0 && height > 0) {
                            // Backpressure: limit pending PixelCopy operations to prevent memory exhaustion
                            if (pendingCopies.get() < 3) {
                                try {
                                    Bitmap frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                                    pendingCopies.incrementAndGet();
                                    
                                    // Try PixelCopy with main thread handler
                                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                    
                                    PixelCopy.request(cameraView, frameBitmap, copyResult -> {
                                        try {
                                            if (copyResult == PixelCopy.SUCCESS) {
                                                // Fix orientation if needed
                                                Bitmap rotatedBitmap = fixBitmapOrientation(frameBitmap);
                                                if (simpleServer != null) {
                                                    simpleServer.getVideoStreamServer().submitFrame(rotatedBitmap);
                                                }
                                                if (rotatedBitmap != frameBitmap) {
                                                    frameBitmap.recycle();
                                                }
                                            } else {
                                                Log.w("MainActivity", "PixelCopy failed with code: " + copyResult);
                                            }
                                        } catch (Exception e) {
                                            Log.e("MainActivity", "Error submitting frame: " + e.getMessage());
                                        } finally {
                                            pendingCopies.decrementAndGet();
                                        }
                                    }, mainHandler);
                                    
                                    frameCounter++;
                                } catch (IllegalArgumentException e) {
                                    Log.w("MainActivity", "Cannot create bitmap with dimensions: " + width + "x" + height);
                                } catch (OutOfMemoryError e) {
                                    Log.e("MainActivity", "Out of memory creating bitmap, skipping frame");
                                    // Force garbage collection
                                    System.gc();
                                }
                                
                            } else {
                                // Skip frame due to backpressure
                                if (frameCounter % 30 == 0) {
                                    Log.w("MainActivity", "Skipping frame due to backpressure (pending: " + pendingCopies.get() + ")");
                                }
                            }
                            
                            long now = System.currentTimeMillis();
                            if (now - lastLogTime >= 3000) {
                                int queueSize = simpleServer != null ? simpleServer.getVideoStreamServer().getQueueSize() : 0;
                                Log.i("MainActivity", "Video: submitted " + frameCounter + " requests, queue: " + queueSize + ", pending: " + pendingCopies.get());
                                lastLogTime = now;
                            }
                        } else {
                            if (frameCounter % 100 == 0) {
                                Log.w("MainActivity", "Camera view has zero dimensions: " + width + "x" + height);
                            }
                        }
                    }
                    
                    // Reduced frame rate: ~10 FPS to prevent memory pressure
                    Thread.sleep(100);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e("MainActivity", "Error in video stream thread: " + e.getMessage(), e);
                    // Brief pause on error to prevent tight error loops
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            Log.i("MainActivity", "Video streaming thread stopped (submitted " + frameCounter + " frame requests)");
        });
        
        videoStreamThread.setName("VideoStreamThread");
        videoStreamThread.start();
    }
    
    /**
     * Broadcast a test message to connected WebSocket clients.
     */
    public void broadcastTestMessage(String message) {
        if (simpleServer != null && simpleServer.getWebSocketServer() != null) {
            simpleServer.getWebSocketServer().broadcast(message);
            Log.d("MainActivity", "Broadcast sent: " + message);
        }
    }
    
    // ========== RobotControlCallback Implementation ==========
    
    @Override
    public void onMove(String direction, float speed) {
        Log.i("MainActivity", "Robot move: " + direction + " speed: " + speed);
        isMoving = true;
        lastCommand = "move:" + direction + ":" + speed;
        
        // Convert speed (0.0-1.0) to motor value (-255 to 255)
        int motorSpeed = (int) (speed * 255);
        int x = 0, y = 0; // X=strafe, Y=forward/backward
        
        switch (direction.toLowerCase()) {
            case "forward":
                Log.d("MainActivity", "Moving forward at speed " + speed);
                y = motorSpeed; // Positive Y for forward
                break;
            case "backward":
                Log.d("MainActivity", "Moving backward at speed " + speed);
                y = -motorSpeed; // Negative Y for backward
                break;
            case "left":
                Log.d("MainActivity", "Moving left at speed " + speed);
                x = -motorSpeed; // Negative X for left strafe
                break;
            case "right":
                Log.d("MainActivity", "Moving right at speed " + speed);
                x = motorSpeed; // Positive X for right strafe
                break;
        }
        
        // Send UDP command: X,Y,R,E (R=0 for movement, E=0 unused)
        sendUdpCommand(x, y, 0, 0);
    }
    
    @Override
    public void onRotate(String direction, float speed) {
        Log.i("MainActivity", "Robot rotate: " + direction + " speed: " + speed);
        isMoving = true;
        lastCommand = "rotate:" + direction + ":" + speed;
        
        // Convert speed (0.0-1.0) to motor value (-255 to 255)
        int motorSpeed = (int) (speed * 255);
        int r = 0; // R=rotation
        
        switch (direction.toLowerCase()) {
            case "left":
                Log.d("MainActivity", "Rotating left at speed " + speed);
                r = -motorSpeed; // Negative R for left rotation
                break;
            case "right":
                Log.d("MainActivity", "Rotating right at speed " + speed);
                r = motorSpeed; // Positive R for right rotation
                break;
        }
        
        // Send UDP command: X,Y,R,E (X=0, Y=0 for pure rotation, E=0 unused)
        sendUdpCommand(0, 0, r, 0);
    }
    
    @Override
    public void onStop() {
        Log.i("MainActivity", "Robot stop");
        isMoving = false;
        lastCommand = "stop";
        
        // Send stop command: all values to 0
        sendUdpCommand(0, 0, 0, 0);
    }
    
    @Override
    public void onCameraSwitch() {
        Log.i("MainActivity", "Camera switch requested");
        int newFacing = 1 - facing;
        yolo11ncnn.closeCamera();
        yolo11ncnn.openCamera(newFacing);
        facing = newFacing;
        lastCommand = "camera_switch";
        
        broadcastTestMessage("Camera switched to " + (newFacing == 0 ? "back" : "front"));
    }
    
    @Override
    public SimpleHttpServer.RobotStatus getRobotStatus() {
        SimpleHttpServer.RobotStatus status = new SimpleHttpServer.RobotStatus();
        status.isMoving = isMoving;
        status.lastCommand = lastCommand;
        status.cameraFacing = facing;
        status.timestamp = System.currentTimeMillis();
        return status;
    }

    private void reload()
    {
        boolean ret_init = yolo11ncnn.loadModel(getAssets(), current_task, current_model, current_cpugpu);
        if (!ret_init)
        {
            Log.e("MainActivity", "yolo11ncnn loadModel failed");
        }
    }
    
    /**
     * Initialize UDP socket for robot communication
     */
    private void initializeUdpSocket() {
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
            udpSocket = new DatagramSocket();
            Log.i("MainActivity", "UDP socket initialized for robot communication");
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to initialize UDP socket: " + e.getMessage());
        }
    }
    
    /**
     * Send UDP command to ESP32 robot
     * @param x X movement value (-100 to 100)
     * @param y Y movement value (-100 to 100) 
     * @param r Rotation value (-100 to 100)
     * @param e Extra value (0 or 1)
     */
    public void sendUdpCommand(int x, int y, int r, int e) {
        if (udpSocket == null || udpSocket.isClosed()) {
            Log.w("MainActivity", "UDP socket not initialized, cannot send command");
            return;
        }
        
        // Get current robot IP from SimpleHttpServer
        String robotIp = simpleServer != null ? simpleServer.getRobotIp() : null;
        if (robotIp == null || robotIp.isEmpty()) {
            Log.w("MainActivity", "Robot IP not configured, cannot send UDP command");
            return;
        }
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Format: X,Y,R,E
                    String command = x + "," + y + "," + r + "," + e;
                    byte[] commandBytes = command.getBytes();
                    
                    InetAddress robotAddress = InetAddress.getByName(robotIp);
                    DatagramPacket packet = new DatagramPacket(
                        commandBytes, 
                        commandBytes.length, 
                        robotAddress, 
                        ROBOT_UDP_PORT
                    );
                    
                    udpSocket.send(packet);
                    Log.d("MainActivity", "Sent UDP command to " + robotIp + ":" + ROBOT_UDP_PORT + " -> " + command);
                    
                    // Update robot state
                    robotX = x;
                    robotY = y;
                    robotR = r;
                    robotE = e;
                    
                } catch (Exception e) {
                    Log.e("MainActivity", "Failed to send UDP command: " + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        // Ensure native camera draws to the surface
        yolo11ncnn.setOutputWindow(holder.getSurface());

        // Inform native code about desired frame rotation and adjust view.
        int nativeRotation = 0;
        try {
            if (width < height) {
                cameraView.setRotation(90f);
                ViewGroup.LayoutParams lp = cameraView.getLayoutParams();
                // swap width/height to match rotated display
                lp.width = height;
                lp.height = width;
                cameraView.setLayoutParams(lp);
                nativeRotation = (facing == 1) ? 270 : 90;
            } else {
                cameraView.setRotation(0f);
                nativeRotation = 0;
            }
            // tell native renderer to rotate frames before processing/drawing
            yolo11ncnn.setDisplayOrientation(nativeRotation);
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to adjust SurfaceView rotation: " + e.getMessage());
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
    }

    @Override
    public void onResume()
    {
        super.onResume();

        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
        {
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }

        yolo11ncnn.openCamera(facing);
    }

    @Override
    public void onPause()
    {
        super.onPause();

        yolo11ncnn.closeCamera();
    }
    
    @Override
    public void onDestroy()
    {
        super.onDestroy();
        
        // Stop the video streaming thread
        if (videoStreamThread != null) {
            videoStreamThread.interrupt();
            try {
                videoStreamThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Stop the simple server when activity is destroyed
        if (simpleServer != null) {
            simpleServer.stopServer();
        }
        
        // Close UDP socket
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            Log.i("MainActivity", "UDP socket closed");
        }
    }
}
