package com.barf.pairing;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.barf.R;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PairingActivity extends Activity implements ImageAnalysis.Analyzer, LifecycleOwner {
    private static final String TAG = "PairingActivity";
    private static final String BARF_PREFIX = "barf://pair";
    private static final long SCAN_TIMEOUT_MS = 30000;

    private PreviewView previewView;
    private TextView statusText;
    private BarcodeScanner barcodeScanner;
    private ExecutorService analysisExecutor;
    private Handler mainHandler;
    private boolean paired = false;
    private LifecycleRegistry lifecycleRegistry;
    private PairingManager pairingManager;
    private WireGuardManager wireGuardManager;

    /** Parsed parameters from a {@code barf://pair?...} QR URI. */
    static class PairingParams {
        String desktopIp;
        String pairKey;
        int port;
        String wgServerIp;
        String wgPublicKey;
        String wgClientIp;
        int wgPort;
    }

    /**
     * Parses a {@code barf://pair?ip=X&key=Y&...} URI into a {@link PairingParams}.
     * Returns null if the URI has no query string or if either {@code ip} or
     * {@code key} are missing.
     */
    static PairingParams parsePairingUri(String uri) {
        if (uri == null || !uri.contains("?")) {
            return null;
        }

        PairingParams params = new PairingParams();
        params.port = 9876;
        params.wgPort = 51820;

        try {
            String query = uri.substring(uri.indexOf('?') + 1);
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length != 2) continue;
                switch (kv[0]) {
                    case "ip":        params.desktopIp   = kv[1]; break;
                    case "key":       params.pairKey     = kv[1]; break;
                    case "port":      params.port        = Integer.parseInt(kv[1]); break;
                    case "wg_ip":     params.wgServerIp  = kv[1]; break;
                    case "wg_key":    params.wgPublicKey = kv[1]; break;
                    case "wg_client": params.wgClientIp  = kv[1]; break;
                    case "wg_port":   params.wgPort      = Integer.parseInt(kv[1]); break;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }

        if (params.desktopIp == null || params.pairKey == null) {
            return null;
        }
        return params;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_pairing);
        previewView = findViewById(R.id.pairingPreviewView);
        statusText = findViewById(R.id.pairingStatusText);

        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        barcodeScanner = BarcodeScanning.getClient();
        analysisExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        pairingManager = new PairingManager(this);
        wireGuardManager = new WireGuardManager(this);

        // Always clear any stale pairing and start fresh camera scan.
        pairingManager.clearPairing();
        startCamera();
        mainHandler.postDelayed(this::onTimeout, SCAN_TIMEOUT_MS);
    }

    @Override
    @NonNull
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init failed: " + e.getMessage());
                runOnUiThread(() -> statusText.setText("Camera error: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (paired) {
            imageProxy.close();
            return;
        }

        @SuppressWarnings("ConstantConditions")
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

        InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(), rotationDegrees);

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String raw = barcode.getRawValue();
                        if (raw != null && raw.startsWith(BARF_PREFIX)) {
                            handleQrCode(raw);
                            break;
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void handleQrCode(String uri) {
        if (paired) return;
        paired = true;

        runOnUiThread(() -> statusText.setText("QR detected! Connecting..."));

        PairingParams params = parsePairingUri(uri);
        if (params == null) {
            runOnUiThread(() -> {
                statusText.setText("Invalid QR code format");
                paired = false;
            });
            return;
        }

        analysisExecutor.execute(() -> {
            String phoneIp = getLocalIpAddress();
            if (phoneIp == null) {
                runOnUiThread(() -> {
                    statusText.setText("No WiFi IP found - check network");
                    paired = false;
                });
                return;
            }

            if (params.wgServerIp != null && params.wgPublicKey != null && params.wgClientIp != null) {
                runOnUiThread(() -> statusText.setText("Establishing WireGuard connection..."));
                try {
                    wireGuardManager.connect(
                            params.wgServerIp, params.wgPublicKey,
                            params.wgClientIp, params.wgPort);
                    Thread.sleep(1000);
                } catch (Exception e) {
                    Log.e(TAG, "WireGuard connection failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        statusText.setText("WireGuard connection failed: " + e.getMessage());
                        paired = false;
                    });
                    return;
                }
            }

            boolean success = sendPairingRequest(
                    params.desktopIp, params.port, params.pairKey, phoneIp);
            if (success) {
                Log.i(TAG, "Paired with desktop at " + params.desktopIp);
                pairingManager.savePairing(
                        params.desktopIp, params.wgServerIp,
                        params.wgPublicKey, params.wgClientIp, params.wgPort);

                runOnUiThread(() -> {
                    statusText.setText("Paired with " + params.desktopIp);
                    Intent result = new Intent();
                    result.putExtra("desktop_ip", params.desktopIp);
                    result.putExtra("phone_ip", phoneIp);
                    result.putExtra("wg_server_ip", params.wgServerIp);
                    result.putExtra("wg_public_key", params.wgPublicKey);
                    result.putExtra("wg_client_ip", params.wgClientIp);
                    result.putExtra("wg_port", params.wgPort);
                    setResult(RESULT_OK, result);
                    finish();
                });
            } else {
                runOnUiThread(() -> {
                    statusText.setText("Failed to reach desktop at " + params.desktopIp);
                    paired = false;
                });
            }
        });
    }

    private boolean sendPairingRequest(String desktopIp, int port, String pairKey, String phoneIp) {
        try {
            URL url = new URL("http://" + desktopIp + ":" + port + "/api/phone-here");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            String json = "{\"pair_key\":\"" + pairKey + "\",\"phone_ip\":\"" + phoneIp + "\",\"phone_port\":8080}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;

        } catch (Exception e) {
            Log.e(TAG, "Pairing POST failed: " + e.getMessage());
            return false;
        }
    }

    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;

            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface.isLoopback() || !iface.isUp()) continue;

                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("169.254")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLocalIpAddress error: " + e.getMessage());
        }
        return null;
    }

    private void onTimeout() {
        if (!paired && !isFinishing()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Scan timed out", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
    }

    @Override
    protected void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        super.onDestroy();
        if (barcodeScanner != null) barcodeScanner.close();
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            try {
                analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
        }
        mainHandler.removeCallbacksAndMessages(null);
    }
}
