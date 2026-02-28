/**
 * VideoStreamServer.java
 * 
 * Simple MJPEG video stream server for streaming camera frames.
 * Provides a simple queue-based frame delivery system.
 */
package com.tencent.yolo11ncnn;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.LinkedBlockingQueue;

public class VideoStreamServer {
    private static final String TAG = "VideoStreamServer";
    private static final int JPEG_QUALITY = 80;
    
    private LinkedBlockingQueue<byte[]> frameQueue;
    private volatile boolean isStreaming = false;
    private long lastFrameTime = 0;
    private static final long MIN_FRAME_INTERVAL = 33; // ~30 FPS
    
    public VideoStreamServer() {
        this.frameQueue = new LinkedBlockingQueue<>(3); // Keep up to 3 frames buffered
    }
    
    /**
     * Start streaming.
     */
    public void start() {
        if (isStreaming) return;
        isStreaming = true;
        Log.i(TAG, "Video stream started");
    }
    
    /**
     * Stop streaming.
     */
    public void stop() {
        isStreaming = false;
        frameQueue.clear();
        Log.i(TAG, "Video stream stopped");
    }
    
    /**
     * Submit a frame for streaming. Non-blocking - drops if queue is full.
     */
    public synchronized void submitFrame(Bitmap frame) {
        if (!isStreaming || frame == null) return;
        
        // Rate limit to ~30 FPS
        long now = System.currentTimeMillis();
        if (now - lastFrameTime < MIN_FRAME_INTERVAL) {
            return;
        }
        lastFrameTime = now;
        
        try {
            // Encode to JPEG
            byte[] jpegData = bitmapToJpeg(frame);
            
            // Try to add, drop if queue full
            if (!frameQueue.offer(jpegData)) {
                // Queue full, drop oldest frame and try again
                frameQueue.poll();
                frameQueue.offer(jpegData);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error encoding frame: " + e.getMessage());
        }
    }
    
    /**
     * Get the next frame with timeout. Returns null if timeout.
     */
    public byte[] getNextFrame(long timeoutMs) throws InterruptedException {
        return frameQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * Get the next frame. Blocks until available.
     */
    public byte[] getNextFrame() throws InterruptedException {
        return frameQueue.take();
    }
    
    /**
     * Peek at next frame without removing.
     */
    public byte[] peekNextFrame() {
        return frameQueue.peek();
    }
    
    /**
     * Get queue size for debugging.
     */
    public int getQueueSize() {
        return frameQueue.size();
    }
    
    /**
     * Convert bitmap to JPEG bytes.
     */
    private byte[] bitmapToJpeg(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
        return stream.toByteArray();
    }
    
    /**
     * Check if streaming.
     */
    public boolean isStreaming() {
        return isStreaming;
    }
}
