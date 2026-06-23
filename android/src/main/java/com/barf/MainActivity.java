// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
// Licensed under the BSD 3-Clause License.
package com.barf;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.content.pm.ActivityInfo;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.barf.camera.CameraManager;
import com.barf.camera.VideoStreamManager;
import com.barf.pairing.PairingManager;
import com.barf.pairing.WireGuardManager;
import com.barf.robot.RobotController;
import com.barf.runtime.JsRuntime;
import com.barf.runtime.WasmRuntime;
import com.barf.serial.UsbSerialManager;
import com.barf.server.PhoneApiServer;

public class MainActivity extends Activity implements SurfaceHolder.Callback, PhoneApiServer.ServerCallback {
    public static final int REQUEST_CAMERA = 100;
    private static final int REQUEST_PAIR = 200;
    private static final String TAG = "MainActivity";

    private YoloBridge yolo = new YoloBridge();
    private CameraManager cameraManager;
    private RobotController robotController;
    private PhoneApiServer apiServer;
    private JsRuntime jsRuntime;
    private WasmRuntime wasmRuntime;
    private VideoStreamManager videoStreamManager;
    private SurfaceView cameraView;
    private static PhoneApiServer sApiServerStatic = null;
    private boolean cameraClosedForPairing = false;
    
    // Pairing info
    private String desktopIp;
    private String phoneIp;
    private PairingManager pairingManager;
    private WireGuardManager wireGuardManager;

    private Spinner spinnerTask, spinnerModel, spinnerCPUGPU;
    private int current_task = 0, current_model = 0, current_cpugpu = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize pairing managers
        pairingManager = new PairingManager(this);
        wireGuardManager = new WireGuardManager(this);

        // Get pairing info from intent
        Intent intent = getIntent();
        if (intent != null) {
            desktopIp = intent.getStringExtra("desktop_ip");
            phoneIp = intent.getStringExtra("phone_ip");
            String wgServerIp = intent.getStringExtra("wg_server_ip");
            String wgPublicKey = intent.getStringExtra("wg_public_key");
            String wgClientIp = intent.getStringExtra("wg_client_ip");
            int wgPort = intent.getIntExtra("wg_port", 51820);
            
            // Re-establish WireGuard connection if needed
            if (wgServerIp != null && wgPublicKey != null && wgClientIp != null && !wireGuardManager.isConnected()) {
                try {
                    wireGuardManager.connect(wgServerIp, wgPublicKey, wgClientIp, wgPort);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to reconnect WireGuard: " + e.getMessage());
                }
            }
        } else if (pairingManager.isPaired()) {
            // Use saved pairing info
            desktopIp = pairingManager.getDesktopIp();
            String wgServerIp = pairingManager.getWgServerIp();
            String wgPublicKey = pairingManager.getWgPublicKey();
            String wgClientIp = pairingManager.getWgClientIp();
            int wgPort = pairingManager.getWgPort();
            
            if (wgServerIp != null && wgPublicKey != null && wgClientIp != null && !wireGuardManager.isConnected()) {
                try {
                    wireGuardManager.connect(wgServerIp, wgPublicKey, wgClientIp, wgPort);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to reconnect WireGuard: " + e.getMessage());
                }
            }
        }

        cameraView = findViewById(R.id.cameraview);
        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(this);
        cameraView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> adjustAspectRatio());

        cameraManager = new CameraManager(yolo);

        Button switchCam = findViewById(R.id.buttonSwitchCamera);
        switchCam.setOnClickListener(v -> {
            cameraManager.switchCamera();
            broadcast("Camera switched to " + (cameraManager.getFacing() == 0 ? "back" : "front"));
        });

        Button pairButton = findViewById(R.id.buttonPair);
        pairButton.setOnClickListener(v -> {
            try {
                cameraManager.close();
                cameraClosedForPairing = true;
            } catch (Exception e) {
                Log.w(TAG, "Error closing camera for pairing: " + e.getMessage());
            }
            Intent pairIntent = new Intent(this, com.barf.pairing.PairingActivity.class);
            startActivityForResult(pairIntent, REQUEST_PAIR);
        });

        spinnerTask = findViewById(R.id.spinnerTask);
        spinnerTask.setOnItemSelectedListener(spinnerListener(0, () -> current_task, p -> current_task = p));
        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerModel.setOnItemSelectedListener(spinnerListener(1, () -> current_model, p -> current_model = p));
        spinnerCPUGPU = findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(spinnerListener(2, () -> current_cpugpu, p -> current_cpugpu = p));

        reload();
        robotController = new RobotController();
        startServer();
    }

    private AdapterView.OnItemSelectedListener spinnerListener(int idx, java.util.function.Supplier<Integer> getter, java.util.function.Consumer<Integer> setter) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos != getter.get()) { setter.accept(pos); reload(); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
    }

    private void adjustAspectRatio() {
        int pw = 1280, ph = 720;
        float ar = (float) pw / ph;
        int vw = cameraView.getWidth(), vh = cameraView.getHeight();
        if (vw == 0 || vh == 0) return;
        int nw = vw, nh = (int) (vw / ar);
        if (nh > vh) { nh = vh; nw = (int) (vh * ar); }
        cameraView.getLayoutParams().width = nw;
        cameraView.getLayoutParams().height = nh;
        cameraView.requestLayout();
    }

    private void startServer() {
        try {
            apiServer = new PhoneApiServer(this, 8080);
            sApiServerStatic = apiServer;
            jsRuntime = new JsRuntime();
            jsRuntime.setCallback(new JsRuntime.JsCommandCallback() {
                @Override public void onMove(String d, float s) { robotController.move(d, s); }
                @Override public void onRotate(String d, float s) { robotController.rotate(d, s); }
                @Override public void onStop() { robotController.stop(); }
            });
            apiServer.setJsRuntime(jsRuntime);
            wasmRuntime = new WasmRuntime();
            apiServer.setWasmRuntime(wasmRuntime);
            apiServer.setCallback(this);

            // Setup USB-Serial for ESP32 communication
            UsbSerialManager usbSerialManager = new UsbSerialManager(this);
            usbSerialManager.setListener(new UsbSerialManager.UsbSerialListener() {
                @Override
                public void onConnected() {
                    Log.i(TAG, "USB Serial connected");
                    if (apiServer != null) {
                        apiServer.broadcastSerialStatus(true);
                    }
                }

                @Override
                public void onDisconnected() {
                    Log.i(TAG, "USB Serial disconnected");
                    if (apiServer != null) {
                        apiServer.broadcastSerialStatus(false);
                    }
                }

                @Override
                public void onMessageReceived(String line) {
                    Log.d(TAG, "USB Serial RX: " + line);
                    if (apiServer != null) {
                        apiServer.broadcastSerialRx(line);
                    }
                }

                @Override
                public void onHeartbeatTimeout() {
                    Log.w(TAG, "USB Serial heartbeat timeout");
                }
            });
            apiServer.setUsbSerialManager(usbSerialManager);

            yolo.registerActivity(this);
            apiServer.start();
            videoStreamManager = new VideoStreamManager(cameraView, apiServer.getVideoStreamServer(), cameraManager);
            videoStreamManager.start();
            Log.i(TAG, "Server started on port 8080");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage());
            Toast.makeText(this, "Server failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void pushDetectionsToScripts(String json) {
        if (sApiServerStatic != null) sApiServerStatic.pushDetections(json);
    }

    private void broadcast(String msg) {
        if (apiServer != null && apiServer.getWebSocketServer() != null)
            apiServer.getWebSocketServer().broadcast(msg);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PAIR) {
            if (resultCode == RESULT_OK && data != null) {
                String desktopIp = data.getStringExtra("desktop_ip");
                String phoneIp = data.getStringExtra("phone_ip");
                Log.i(TAG, "Paired with desktop at " + desktopIp + " (phone IP: " + phoneIp + ")");
                Toast.makeText(this, "Paired with " + desktopIp, Toast.LENGTH_SHORT).show();
            }
            // Always re-open camera after pairing attempt
            cameraClosedForPairing = false;
            cameraManager.open();
        }
    }

    private void reload() {
        if (!yolo.loadModel(getAssets(), current_task, current_model, current_cpugpu))
            Log.e(TAG, "loadModel failed");
    }

    // ========== ServerCallback ==========
    @Override public void onMove(String d, float s) { robotController.move(d, s); }
    @Override public void onRotate(String d, float s) { robotController.rotate(d, s); }
    @Override public void onSwitchCamera() {
        try { cameraManager.switchCamera(); } catch (Exception e) { Log.w(TAG, "switchCamera error: " + e.getMessage()); }
    }
    @Override public int getCameraFacing() { return cameraManager.getFacing(); }

    // onStop() serves both Activity lifecycle and ServerCallback interface
    @Override
    public void onStop() {
        super.onStop();
        robotController.stop();
        cameraClosedForPairing = false;
    }

    // ========== SurfaceHolder.Callback ==========
    @Override public void surfaceCreated(SurfaceHolder h) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}

    @Override
    public void surfaceChanged(SurfaceHolder h, int fmt, int w, int h_) {
        yolo.setOutputWindow(h.getSurface());
        int rot = 0;
        try {
            if (w < h_) {
                cameraView.setRotation(90f);
                ViewGroup.LayoutParams lp = cameraView.getLayoutParams();
                lp.width = h_; lp.height = w;
                cameraView.setLayoutParams(lp);
                rot = (cameraManager.getFacing() == 1) ? 270 : 90;
            }
            cameraManager.setDisplayOrientation(rot);
        } catch (Exception e) {
            Log.w(TAG, "surfaceChanged error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        // Don't open camera if we're returning from pairing (onActivityResult handles it)
        if (!cameraClosedForPairing) {
            cameraManager.open();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!cameraClosedForPairing) {
            try { cameraManager.close(); } catch (Exception e) { Log.w(TAG, "close error: " + e.getMessage()); }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (videoStreamManager != null) videoStreamManager.stop();
        if (apiServer != null) apiServer.stop();
        if (robotController != null) robotController.shutdown();
    }
}
