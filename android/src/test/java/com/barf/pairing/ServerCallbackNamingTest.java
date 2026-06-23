package com.barf.pairing;

import com.barf.server.PhoneApiServer;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * RED-phase tests for Bug 1: ServerCallback.onStop() / Activity.onStop() naming collision.
 *
 * The {@code PhoneApiServer.ServerCallback} interface currently defines {@code void onStop()},
 * which clashes with the {@code Activity.onStop()} lifecycle method when {@code MainActivity}
 * implements both. The fix is to rename it to {@code onRobotStop()}.
 *
 * These tests FAIL until the rename is applied to the production interface.
 */
public class ServerCallbackNamingTest {

    /**
     * {@code PhoneApiServer.ServerCallback} must NOT declare a method named {@code onStop}.
     *
     * While an {@code onStop} method exists on the interface today, this test documents that it
     * must be removed (renamed to {@code onRobotStop}) to eliminate the Activity lifecycle
     * collision.
     */
    @Test
    public void serverCallback_doesNotHaveMethodNamedOnStop() {
        Class<?> callbackInterface = PhoneApiServer.ServerCallback.class;

        for (Method method : callbackInterface.getDeclaredMethods()) {
            if ("onStop".equals(method.getName())) {
                fail("PhoneApiServer.ServerCallback must NOT declare 'onStop()' — "
                        + "this name collides with Activity.onStop() lifecycle method. "
                        + "Rename it to 'onRobotStop()'.");
            }
        }
    }

    /**
     * {@code PhoneApiServer.ServerCallback} must declare a no-arg method named {@code onRobotStop}.
     *
     * This is the intended replacement name that avoids the Activity lifecycle collision.
     */
    @Test
    public void serverCallback_hasMethodNamedOnRobotStop() {
        Class<?> callbackInterface = PhoneApiServer.ServerCallback.class;

        Method found = null;
        for (Method method : callbackInterface.getDeclaredMethods()) {
            if ("onRobotStop".equals(method.getName())
                    && method.getParameterCount() == 0) {
                found = method;
                break;
            }
        }

        assertNotNull(
                "PhoneApiServer.ServerCallback must declare 'void onRobotStop()' "
                        + "as the renamed replacement for the colliding 'onStop()' method.",
                found);
    }

    /**
     * {@code onRobotStop} must be the only robot-stop method — there must not be
     * a residual {@code onStop} alongside it. (Guards against both names existing at once.)
     */
    @Test
    public void serverCallback_hasOnRobotStopButNotOnStop() {
        Class<?> callbackInterface = PhoneApiServer.ServerCallback.class;

        boolean hasOnStop = false;
        boolean hasOnRobotStop = false;

        for (Method method : callbackInterface.getDeclaredMethods()) {
            if ("onStop".equals(method.getName())) {
                hasOnStop = true;
            }
            if ("onRobotStop".equals(method.getName()) && method.getParameterCount() == 0) {
                hasOnRobotStop = true;
            }
        }

        if (hasOnStop) {
            fail("ServerCallback still has 'onStop()' — it must be renamed to 'onRobotStop()'.");
        }
        if (!hasOnRobotStop) {
            fail("ServerCallback is missing 'onRobotStop()' — rename 'onStop()' to 'onRobotStop()'.");
        }
    }
}
