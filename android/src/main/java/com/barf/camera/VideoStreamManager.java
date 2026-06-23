package com.barf.camera;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import com.barf.VideoStreamServer;

/**
 * Manages the video streaming loop: captures frames from SurfaceView via PixelCopy
 * and feeds them to VideoStreamServer for MJPEG distribution.
 *
 * Performance notes:
 * - PixelCopy callbacks run on a dedicated HandlerThread (not the main thread)
 *   to avoid contending with the YOLO/UI pipeline.
 * - Bitmaps are scaled to TARGET_WIDTH before encoding to reduce JPEG size and
 *   network transfer time.
 * - Capture loop runs at ~30fps (33ms sleep); the pendingCopies guard prevents
 *   runaway queuing if PixelCopy is slower than the loop.
 */
public class VideoStreamManager {
    private static final String TAG = "VideoStreamManager";
    private static final int TARGET_WIDTH = 640;

    private final SurfaceView cameraView;
    private final VideoStreamServer videoStreamServer;
    private final CameraManager cameraManager;

    private Thread videoThread;
    private volatile boolean running = false;

    // Dedicated thread for PixelCopy callbacks — keeps main thread free for YOLO/UI.
    private HandlerThread callbackThread;
    // Cached handler pointing at callbackThread — created once in start(), not per-frame.
    private Handler callbackHandler;

    public VideoStreamManager(SurfaceView cameraView, VideoStreamServer videoStreamServer, CameraManager cameraManager) {
        this.cameraView = cameraView;
        this.videoStreamServer = videoStreamServer;
        this.cameraManager = cameraManager;
    }

    public void start() {
        if (running) return;
        running = true;

        // Start the dedicated callback thread before the capture loop.
        callbackThread = new HandlerThread("PixelCopyCallback");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());

        videoThread = new Thread(() -> {
            Log.i(TAG, "Video streaming thread started");
            long frameCounter = 0;
            long lastLogTime = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicInteger pendingCopies = new java.util.concurrent.atomic.AtomicInteger(0);

            while (running) {
                try {
                    if (cameraView != null && cameraView.getHolder().getSurface().isValid()) {
                        int srcWidth = cameraView.getWidth();
                        int srcHeight = cameraView.getHeight();

                        if (srcWidth > 0 && srcHeight > 0 && pendingCopies.get() < 2) {
                            // Scale down to TARGET_WIDTH to reduce JPEG size and encoding time.
                            int dstWidth = Math.min(TARGET_WIDTH, srcWidth);
                            int dstHeight = (int) (srcHeight * ((float) dstWidth / srcWidth));
                            if (dstHeight <= 0) dstHeight = 1;

                            Bitmap bitmap = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888);
                            pendingCopies.incrementAndGet();

                            // callbackHandler routes callbacks to callbackThread, not main thread.
                            PixelCopy.request(cameraView, bitmap, result -> {
                                try {
                                    if (result == PixelCopy.SUCCESS) {
                                        Bitmap rotated = fixOrientation(bitmap);
                                        videoStreamServer.submitFrame(rotated);
                                        // Always recycle both bitmaps after JPEG encoding
                                        // to reduce GC pressure at 30fps.
                                        if (rotated != bitmap) rotated.recycle();
                                        bitmap.recycle();
                                    } else {
                                        bitmap.recycle();
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Error submitting frame: " + e.getMessage());
                                    bitmap.recycle();
                                } finally {
                                    pendingCopies.decrementAndGet();
                                }
                            }, callbackHandler);

                            frameCounter++;
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastLogTime >= 3000) {
                            Log.i(TAG, "Video: submitted " + frameCounter + " frames, pending: " + pendingCopies.get());
                            lastLogTime = now;
                        }
                    }

                    Thread.sleep(33); // ~30fps capture rate (was 100ms = 10fps)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in video stream: " + e.getMessage(), e);
                    try { Thread.sleep(500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            Log.i(TAG, "Video streaming thread stopped");
        });

        videoThread.setName("VideoStream");
        videoThread.start();
    }

    public void stop() {
        running = false;
        if (videoThread != null) {
            videoThread.interrupt();
            try { videoThread.join(2000); } catch (InterruptedException ignored) {}
            videoThread = null;
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
            callbackHandler = null;
        }
    }

    private Bitmap fixOrientation(Bitmap src) {
        if (src == null || src.getWidth() >= src.getHeight()) return src;
        int degrees = (cameraManager.getFacing() == 1) ? 270 : 90;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }
}
