package com.barf.server;

import com.barf.SimpleWebSocketServer;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RED-phase tests for Bug 3: {@code SimpleWebSocketServer.onClose()} removes clients but
 * never notifies {@code MainActivity} that the desktop disconnected.
 *
 * The fix requires:
 * 1. A nested {@code ConnectionListener} interface on {@code SimpleWebSocketServer}
 *    with {@code onDesktopConnected()} and {@code onDesktopDisconnected()}.
 * 2. {@code setConnectionListener(ConnectionListener)} on {@code SimpleWebSocketServer}.
 * 3. {@code onOpen()} fires {@code onDesktopConnected()}.
 * 4. {@code onClose()} fires {@code onDesktopDisconnected()} only when the last client leaves.
 *
 * All structural tests fail with AssertionError until the interface is added.
 * All behavioral tests fail until the callbacks are wired up.
 *
 * Reflection is used throughout to avoid compile errors from referencing the
 * not-yet-existing {@code ConnectionListener} type directly.
 */
public class WebSocketDisconnectTest {

    // ── interface existence (reflection) ──────────────────────────────────────

    /**
     * {@code SimpleWebSocketServer} must declare a nested interface named
     * {@code ConnectionListener}.
     */
    @Test
    public void simpleWebSocketServer_hasConnectionListenerInterface() {
        Class<?> found = findConnectionListenerClass();
        assertNotNull(
                "SimpleWebSocketServer must declare a nested interface 'ConnectionListener'.",
                found);
    }

    /**
     * {@code ConnectionListener} must declare {@code onDesktopConnected()}.
     */
    @Test
    public void connectionListener_hasOnDesktopConnectedMethod() {
        Class<?> listenerInterface = requireConnectionListenerClass();

        Method found = null;
        for (Method m : listenerInterface.getDeclaredMethods()) {
            if ("onDesktopConnected".equals(m.getName()) && m.getParameterCount() == 0) {
                found = m;
                break;
            }
        }
        assertNotNull(
                "ConnectionListener must declare 'void onDesktopConnected()'.",
                found);
    }

    /**
     * {@code ConnectionListener} must declare {@code onDesktopDisconnected()}.
     */
    @Test
    public void connectionListener_hasOnDesktopDisconnectedMethod() {
        Class<?> listenerInterface = requireConnectionListenerClass();

        Method found = null;
        for (Method m : listenerInterface.getDeclaredMethods()) {
            if ("onDesktopDisconnected".equals(m.getName()) && m.getParameterCount() == 0) {
                found = m;
                break;
            }
        }
        assertNotNull(
                "ConnectionListener must declare 'void onDesktopDisconnected()'.",
                found);
    }

    /**
     * {@code SimpleWebSocketServer} must expose
     * {@code setConnectionListener(ConnectionListener)}.
     */
    @Test
    public void simpleWebSocketServer_hasSetConnectionListenerMethod() {
        Class<?> listenerInterface = requireConnectionListenerClass();

        Method found = null;
        for (Method m : SimpleWebSocketServer.class.getDeclaredMethods()) {
            if ("setConnectionListener".equals(m.getName())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].equals(listenerInterface)) {
                found = m;
                break;
            }
        }
        assertNotNull(
                "SimpleWebSocketServer must declare 'void setConnectionListener(ConnectionListener)'.",
                found);
    }

    // ── behavioral tests ──────────────────────────────────────────────────────

    /**
     * When {@code onOpen()} is called, the listener's {@code onDesktopConnected()}
     * must fire exactly once.
     */
    @Test
    public void onOpen_firesOnDesktopConnected() throws Exception {
        Class<?> listenerInterface = requireConnectionListenerClass();
        AtomicInteger connectedCount = new AtomicInteger(0);
        AtomicInteger disconnectedCount = new AtomicInteger(0);

        Object listener = buildListener(listenerInterface, connectedCount, disconnectedCount);
        TestableSimpleWebSocketServer server = new TestableSimpleWebSocketServer(8099);
        setListener(server, listener);

        WebSocket mockConn = buildMockWebSocket();
        ClientHandshake mockHandshake = mock(ClientHandshake.class);
        server.onOpen(mockConn, mockHandshake);

        assertEquals("onDesktopConnected() must be called once when a client connects.",
                1, connectedCount.get());
    }

    /**
     * When {@code onClose()} is called and the client list drops to zero, the
     * listener's {@code onDesktopDisconnected()} must fire exactly once.
     */
    @Test
    public void onClose_withNoClientsRemaining_firesOnDesktopDisconnected() throws Exception {
        Class<?> listenerInterface = requireConnectionListenerClass();
        AtomicInteger connectedCount = new AtomicInteger(0);
        AtomicInteger disconnectedCount = new AtomicInteger(0);

        Object listener = buildListener(listenerInterface, connectedCount, disconnectedCount);
        TestableSimpleWebSocketServer server = new TestableSimpleWebSocketServer(8099);
        setListener(server, listener);

        WebSocket mockConn = buildMockWebSocket();
        ClientHandshake mockHandshake = mock(ClientHandshake.class);

        // Connect then disconnect — leaves zero clients
        server.onOpen(mockConn, mockHandshake);
        server.onClose(mockConn, 1000, "normal", true);

        assertEquals(
                "onDesktopDisconnected() must be called once when the last client disconnects.",
                1, disconnectedCount.get());
    }

    /**
     * When {@code onClose()} is called but other clients remain connected, the
     * listener's {@code onDesktopDisconnected()} must NOT fire.
     */
    @Test
    public void onClose_withOtherClientsRemaining_doesNotFireOnDesktopDisconnected()
            throws Exception {
        Class<?> listenerInterface = requireConnectionListenerClass();
        AtomicInteger connectedCount = new AtomicInteger(0);
        AtomicInteger disconnectedCount = new AtomicInteger(0);

        Object listener = buildListener(listenerInterface, connectedCount, disconnectedCount);
        TestableSimpleWebSocketServer server = new TestableSimpleWebSocketServer(8099);
        setListener(server, listener);

        WebSocket firstConn = buildMockWebSocket();
        WebSocket secondConn = buildMockWebSocket();
        ClientHandshake mockHandshake = mock(ClientHandshake.class);

        // Connect two clients
        server.onOpen(firstConn, mockHandshake);
        server.onOpen(secondConn, mockHandshake);

        // Only the first disconnects — second is still present
        server.onClose(firstConn, 1000, "normal", true);

        assertEquals(
                "onDesktopDisconnected() must NOT fire when other clients remain connected.",
                0, disconnectedCount.get());
    }

    /**
     * When no listener has been set, {@code onOpen()} and {@code onClose()} must not throw.
     */
    @Test
    public void onOpenAndClose_withNoListenerSet_doesNotThrow() throws Exception {
        // This test requires setConnectionListener to exist, but does not call it.
        requireConnectionListenerClass(); // ensures interface exists (will fail if not)

        TestableSimpleWebSocketServer server = new TestableSimpleWebSocketServer(8099);
        // Deliberately no setConnectionListener() call

        WebSocket mockConn = buildMockWebSocket();
        ClientHandshake mockHandshake = mock(ClientHandshake.class);

        server.onOpen(mockConn, mockHandshake);
        server.onClose(mockConn, 1000, "normal", true);
        // If we reach here without an NPE the test passes
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Returns the ConnectionListener nested interface, or null if absent. */
    private static Class<?> findConnectionListenerClass() {
        for (Class<?> nested : SimpleWebSocketServer.class.getDeclaredClasses()) {
            if ("ConnectionListener".equals(nested.getSimpleName()) && nested.isInterface()) {
                return nested;
            }
        }
        return null;
    }

    /** Asserts and returns the ConnectionListener interface — fails immediately if absent. */
    private static Class<?> requireConnectionListenerClass() {
        Class<?> c = findConnectionListenerClass();
        assertNotNull(
                "SimpleWebSocketServer.ConnectionListener interface does not exist yet — "
                        + "create it as part of the Bug 3 fix.",
                c);
        return c;
    }

    /**
     * Builds a dynamic proxy implementing the ConnectionListener interface that
     * increments the provided counters on each callback.
     */
    private static Object buildListener(Class<?> listenerInterface,
                                        AtomicInteger connectedCount,
                                        AtomicInteger disconnectedCount) {
        return Proxy.newProxyInstance(
                listenerInterface.getClassLoader(),
                new Class<?>[]{listenerInterface},
                (proxy, method, args) -> {
                    if ("onDesktopConnected".equals(method.getName())) {
                        connectedCount.incrementAndGet();
                    } else if ("onDesktopDisconnected".equals(method.getName())) {
                        disconnectedCount.incrementAndGet();
                    }
                    return null;
                });
    }

    /**
     * Invokes {@code setConnectionListener(listener)} on the server via reflection.
     */
    private static void setListener(SimpleWebSocketServer server, Object listener)
            throws Exception {
        Class<?> listenerInterface = requireConnectionListenerClass();
        Method setter = null;
        for (Method m : SimpleWebSocketServer.class.getDeclaredMethods()) {
            if ("setConnectionListener".equals(m.getName())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].equals(listenerInterface)) {
                setter = m;
                break;
            }
        }
        assertNotNull("setConnectionListener(ConnectionListener) must exist on SimpleWebSocketServer",
                setter);
        setter.setAccessible(true);
        setter.invoke(server, listener);
    }

    /**
     * Builds a mock {@link WebSocket} that returns a non-null remote address,
     * preventing NPEs in the server's logging code.
     */
    private static WebSocket buildMockWebSocket() {
        WebSocket conn = mock(WebSocket.class);
        when(conn.getRemoteSocketAddress())
                .thenReturn(new InetSocketAddress("127.0.0.1", 54321));
        return conn;
    }

    /**
     * Subclass of {@link SimpleWebSocketServer} that avoids binding to a real port.
     * {@code WebSocketServer(InetSocketAddress)} does not bind until {@code start()} is
     * called, so construction is safe in a JVM unit-test context.
     */
    private static class TestableSimpleWebSocketServer extends SimpleWebSocketServer {
        TestableSimpleWebSocketServer(int port) {
            super(port);
        }
    }
}
