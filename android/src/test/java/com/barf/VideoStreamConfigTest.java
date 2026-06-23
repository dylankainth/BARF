package com.barf;

import com.barf.camera.VideoStreamManager;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Configuration/behaviour tests for video streaming performance fixes.
 *
 * All 5 tests are RED: they describe the desired post-fix state.
 * They will FAIL until the production code is updated.
 *
 * No Android instrumentation required — pure JVM reflection checks.
 * Android SDK calls return defaults via returnDefaultValues = true.
 */
public class VideoStreamConfigTest {

    // ── Test 1: JPEG_QUALITY must be ≤ 70 ────────────────────────────────────

    /**
     * JPEG_QUALITY is currently 80, which is too high for real-time streaming.
     * After the fix it must be ≤ 70 to reduce encode time and bandwidth.
     */
    @Test
    public void jpegQuality_isAtMost70() throws Exception {
        Field field = VideoStreamServer.class.getDeclaredField("JPEG_QUALITY");
        field.setAccessible(true);
        int quality = (int) field.get(null);

        assertTrue(
                "JPEG_QUALITY must be ≤ 70 for streaming performance, but was " + quality,
                quality <= 70
        );
    }

    // ── Test 2: frameQueue capacity must be ≤ 2 ──────────────────────────────

    /**
     * The frame queue is currently sized 3 which can introduce latency spikes.
     * After the fix, its capacity must be ≤ 2 so stale frames are dropped sooner.
     */
    @Test
    public void frameQueueCapacity_isAtMost2() throws Exception {
        VideoStreamServer server = new VideoStreamServer();
        server.start();

        // Queue is empty right after start(), so:
        //   capacity = remainingCapacity() + size() = remainingCapacity() + 0
        Field queueField = VideoStreamServer.class.getDeclaredField("frameQueue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        LinkedBlockingQueue<byte[]> queue = (LinkedBlockingQueue<byte[]>) queueField.get(server);

        int size = server.getQueueSize();
        int remaining = queue.remainingCapacity();
        int capacity = remaining + size;

        assertTrue(
                "frameQueue capacity must be ≤ 2 to reduce latency, but was " + capacity,
                capacity <= 2
        );
    }

    // ── Test 3: submitFrame must NOT be synchronized ──────────────────────────

    /**
     * submitFrame(Bitmap) is currently declared synchronized, which means every
     * caller blocks on the same monitor. After the fix, the method must be
     * non-synchronized — internal thread-safety is handled by the queue itself.
     */
    @Test
    public void submitFrame_isNotSynchronized() throws Exception {
        Method submitFrame = VideoStreamServer.class.getDeclaredMethod(
                "submitFrame", android.graphics.Bitmap.class);

        boolean isSynchronized = Modifier.isSynchronized(submitFrame.getModifiers());

        if (isSynchronized) {
            fail("submitFrame(Bitmap) must NOT be synchronized — "
                    + "the LinkedBlockingQueue provides its own thread-safety. "
                    + "Remove the 'synchronized' modifier.");
        }
    }

    // ── Test 4: VideoStreamManager must have a callbackHandler field ──────────

    /**
     * VideoStreamManager currently creates a new Handler(Looper.getMainLooper())
     * inside the capture loop on every iteration, which re-routes all PixelCopy
     * callbacks through the main thread and causes jank.
     *
     * After the fix, a dedicated HandlerThread must be used, exposed as a field
     * named 'callbackHandler' of type android.os.Handler.
     * This field does not exist yet → test FAILS.
     */
    @Test
    public void videoStreamManager_doesNotUseMainLooperForPixelCopy() {
        Field callbackHandlerField = null;
        try {
            callbackHandlerField = VideoStreamManager.class.getDeclaredField("callbackHandler");
        } catch (NoSuchFieldException e) {
            fail("VideoStreamManager must have a field named 'callbackHandler' "
                    + "(a pre-created Handler backed by a dedicated HandlerThread, "
                    + "not Looper.getMainLooper()). Field does not exist yet.");
        }

        assertNotNull("callbackHandler field must be declared on VideoStreamManager",
                callbackHandlerField);

        boolean isHandlerType = callbackHandlerField.getType()
                .getName()
                .equals("android.os.Handler");
        assertTrue(
                "callbackHandler must be of type android.os.Handler, was "
                        + callbackHandlerField.getType().getName(),
                isHandlerType
        );
    }

    // ── Test 5: callbackHandler field must exist and be instance-level ────────

    /**
     * Verifies that 'callbackHandler' is an instance field (not static, not
     * local) on VideoStreamManager, confirming it is cached across loop
     * iterations rather than re-created per frame.
     * Field does not exist yet → test FAILS.
     */
    @Test
    public void videoStreamManager_hasCachedHandler() {
        Field callbackHandlerField = null;
        try {
            callbackHandlerField = VideoStreamManager.class.getDeclaredField("callbackHandler");
        } catch (NoSuchFieldException e) {
            fail("VideoStreamManager must declare an instance field 'callbackHandler' "
                    + "of type android.os.Handler so that the Handler is created once "
                    + "and reused, not allocated on every frame. Field is missing.");
        }

        assertNotNull(callbackHandlerField);

        boolean isStatic = Modifier.isStatic(callbackHandlerField.getModifiers());
        if (isStatic) {
            fail("callbackHandler must be an instance field, not static — "
                    + "each VideoStreamManager instance should own its own HandlerThread.");
        }

        boolean isHandlerType = callbackHandlerField.getType()
                .getName()
                .equals("android.os.Handler");
        assertTrue(
                "callbackHandler must be of type android.os.Handler, was "
                        + callbackHandlerField.getType().getName(),
                isHandlerType
        );
    }
}
