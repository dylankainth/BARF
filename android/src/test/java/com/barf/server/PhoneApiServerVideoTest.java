package com.barf.server;

import android.content.Context;

import com.barf.VideoStreamServer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the /api/video MJPEG streaming endpoint.
 *
 * All tests in this class describe behaviour that does NOT yet exist in
 * PhoneApiServer. They are expected to FAIL (RED phase) until the feature is
 * implemented.
 *
 * Strategy
 * --------
 * PhoneApiServer.serve() is pure-Java routing logic. We instantiate the server
 * without starting it (no bind / no threads) and call serve() directly with a
 * mocked IHTTPSession, inspecting the returned Response object.
 *
 * Android SDK calls (Log.i, etc.) return default values (null / 0 / false)
 * thanks to android.testOptions.unitTests.returnDefaultValues = true in
 * build.gradle.
 */
@RunWith(MockitoJUnitRunner.class)
public class PhoneApiServerVideoTest {

    @Mock
    private Context mockContext;

    private PhoneApiServer server;

    @Before
    public void setUp() {
        // Port 0 → OS picks ephemeral port; we never actually bind in these tests.
        server = new PhoneApiServer(mockContext, 0);
    }

    // ── /api/video — content-type ─────────────────────────────────────────────

    /**
     * GET /api/video must return Content-Type multipart/x-mixed-replace;boundary=frame
     * so that browsers and the Tauri frontend can parse the MJPEG stream.
     */
    @Test
    public void getApiVideo_returnsMjpegContentType() {
        IHTTPSession session = buildSession(Method.GET, "/api/video");

        Response response = server.serve(session);

        assertNotNull("Response must not be null", response);
        String mimeType = response.getMimeType();
        assertNotNull("MIME type must be set", mimeType);
        assertTrue(
                "Content-Type must be 'multipart/x-mixed-replace;boundary=frame', was: " + mimeType,
                mimeType.contains("multipart/x-mixed-replace")
                        && mimeType.contains("boundary=frame")
        );
    }

    // ── /api/video — HTTP status ──────────────────────────────────────────────

    /**
     * GET /api/video must return HTTP 200 OK.
     */
    @Test
    public void getApiVideo_returnsHttp200() {
        IHTTPSession session = buildSession(Method.GET, "/api/video");

        Response response = server.serve(session);

        assertNotNull("Response must not be null", response);
        assertEquals(
                "Status must be 200 OK",
                Response.Status.OK,
                response.getStatus()
        );
    }

    // ── /api/video — no frames queued ────────────────────────────────────────

    /**
     * GET /api/video must not crash when no frames have been submitted yet.
     * The correct content-type header must still be present.
     */
    @Test
    public void getApiVideo_withNoFramesQueued_stillReturnsCorrectContentType() {
        // VideoStreamServer queue is empty at construction — no submitFrame() called.
        IHTTPSession session = buildSession(Method.GET, "/api/video");

        Response response = server.serve(session);

        assertNotNull("Response must not be null even with an empty frame queue", response);
        String mimeType = response.getMimeType();
        assertNotNull("MIME type must be set even with no frames queued", mimeType);
        assertTrue(
                "Content-Type must be multipart/x-mixed-replace even with empty queue, was: " + mimeType,
                mimeType.contains("multipart/x-mixed-replace")
        );
    }

    // ── /api/status — regression check ───────────────────────────────────────

    /**
     * GET /api/status must still return HTTP 200 after the video endpoint is added.
     * Ensures the video routing change does not break the existing status endpoint.
     */
    @Test
    public void getApiStatus_stillReturnsHttp200AfterVideoEndpointIsAdded() {
        IHTTPSession session = buildSession(Method.GET, "/api/status");

        Response response = server.serve(session);

        assertNotNull("Status response must not be null", response);
        assertEquals(
                "GET /api/status must still return 200 after adding /api/video",
                Response.Status.OK,
                response.getStatus()
        );
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a minimal mock IHTTPSession for the given method and URI.
     */
    private IHTTPSession buildSession(Method method, String uri) {
        IHTTPSession session = mock(IHTTPSession.class);
        when(session.getMethod()).thenReturn(method);
        when(session.getUri()).thenReturn(uri);
        return session;
    }
}
