/**
 * RhinoScriptExecutor.java
 * 
 * Executes JavaScript scripts using Mozilla Rhino engine with a robot control API.
 * Provides functions: move(), rotate(), stop(), wait(), log(), print()
 */
package com.tencent.yolo11ncnn;

import android.util.Log;
import org.mozilla.javascript.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

public class RhinoScriptExecutor {
    private static final String TAG = "RhinoScriptExecutor";
    
    private final RobotApi robotApi;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final StringBuilder output = new StringBuilder();
    private String lastError = null;
    private Thread executionThread = null;
    private Context rhinoContext = null;
    // For YOLO detection API
    private volatile String lastDetectionsJson = "[]";
    private volatile ScriptableObject globalScope = null;
    private final Gson gson = new Gson();
    
    /**
     * Callback interface for robot control commands.
     */
    public interface RobotCommandCallback {
        void onMove(String direction, float speed);
        void onRotate(String direction, float speed);
        void onStop();
    }
    
    public RhinoScriptExecutor(RobotCommandCallback callback) {
        this.robotApi = new RobotApi(callback);
    }
    
    /**
     * Check if a script is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get the script output.
     */
    public String getOutput() {
        return output.toString();
    }
    
    /**
     * Get the last error message.
     */
    public String getLastError() {
        return lastError;
    }
    
    /**
     * Clear output and errors.
     */
    public void clearOutput() {
        output.setLength(0);
        lastError = null;
    }
    
    /**
     * Execute a JavaScript script in a background thread.
     */
    public void execute(String script) {
        if (running.get()) {
            throw new IllegalStateException("Script is already running");
        }
        
        clearOutput();
        running.set(true);
        
        executionThread = new Thread(() -> {
            try {
                executeScript(script);
            } catch (Exception e) {
                lastError = e.getMessage();
                appendOutput("ERROR: " + e.getMessage());
                Log.e(TAG, "Script execution error", e);
            } finally {
                running.set(false);
                // Ensure robot stops when script ends
                robotApi.stop();
            }
        });
        executionThread.start();
    }
    
    /**
     * Stop the currently running script.
     */
    public void stop() {
        if (!running.get()) return;
        
        running.set(false);
        
        // Interrupt the execution thread
        if (executionThread != null) {
            executionThread.interrupt();
        }
        
        // Interrupt Rhino context if available
        if (rhinoContext != null) {
            try {
                // This causes Rhino to throw an exception at the next instruction
                rhinoContext.setGeneratingDebug(false);
            } catch (Exception e) {
                // Ignore
            }
        }
        
        // Stop the robot
        robotApi.stop();
        appendOutput("Script stopped by user");
    }

    /**
     * Push detection JSON from Java side into the Rhino executor.
     * This will update `lastDetectionsJson` and invoke any registered JS callback set via `onDetection(fn)`.
     */
    public void pushDetections(String detectionsJson) {
        if (detectionsJson == null) detectionsJson = "[]";
        lastDetectionsJson = detectionsJson;

        ScriptableObject scope;
        synchronized (this) {
            scope = this.globalScope;
        }

        if (scope == null) {
            // no active script scope to call into
            return;
        }

        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);

            Object cb = ScriptableObject.getProperty(scope, "__yolo_onDetection");
            if (cb instanceof Function) {
                Function fn = (Function) cb;
                Object arg = Context.javaToJS(gson.fromJson(detectionsJson, Object.class), scope);
                try {
                    fn.call(cx, scope, scope, new Object[]{arg});
                } catch (Exception e) {
                    appendOutput("Error calling detection callback: " + e.getMessage());
                }
            }
        } catch (JsonSyntaxException e) {
            appendOutput("Invalid detection JSON: " + e.getMessage());
        } finally {
            Context.exit();
        }
    }
    
    /**
     * Execute the script using Rhino.
     */
    private void executeScript(String script) {
        appendOutput("Starting script execution...");
        
        // Create Rhino context (Android-compatible)
        rhinoContext = Context.enter();
        try {
            // Disable JIT compilation for Android compatibility
            rhinoContext.setOptimizationLevel(-1);
            
            // Create a fresh scope
            ScriptableObject scope = rhinoContext.initStandardObjects();
            
            // Inject the robot API object
            Object wrappedApi = Context.javaToJS(robotApi, scope);
            ScriptableObject.putProperty(scope, "robot", wrappedApi);
            
            // Inject convenience functions directly into scope
            injectConvenienceFunctions(rhinoContext, scope);
            
            // Execute the script
            rhinoContext.evaluateString(scope, script, "script", 1, null);
            
            appendOutput("Script completed successfully");
            
        } catch (RhinoException e) {
            lastError = e.getMessage();
            appendOutput("Script error at line " + e.lineNumber() + ": " + e.details());
            Log.e(TAG, "Rhino error", e);
        } catch (Exception e) {
            lastError = e.getMessage();
            appendOutput("Error: " + e.getMessage());
            Log.e(TAG, "Execution error", e);
        } finally {
            // clear stored scope
            synchronized (this) {
                this.globalScope = null;
            }
            Context.exit();
            rhinoContext = null;
        }
    }
    
    /**
     * Inject convenience functions so users can call move() instead of robot.move()
     */
    private void injectConvenienceFunctions(Context cx, ScriptableObject scope) {
        String helperFunctions = 
            // Movement functions
            "function move(direction, speed) { robot.move(direction, speed); }\n" +
            "function rotate(direction, speed) { robot.rotate(direction, speed); }\n" +
            "function stop() { robot.stop(); }\n" +
            
            // Timing function (use sleep to avoid calling Object.wait)
            "function wait(ms) { robot.sleep(ms); }\n" +
            "function sleep(ms) { robot.sleep(ms); }\n" +
            "function delay(ms) { robot.sleep(ms); }\n" +
            
            // Output functions
            "function log(msg) { robot.log(msg); }\n" +
            "function print(msg) { robot.log(msg); }\n" +
            
            // Helper for repeating actions
            "function repeat(n, fn) { for(var i = 0; i < n; i++) { fn(i); } }\n" +
            
            // Direction constants
            "var FORWARD = 'forward';\n" +
            "var BACKWARD = 'backward';\n" +
            "var LEFT = 'left';\n" +
            "var RIGHT = 'right';\n" +
            // YOLO detection helpers
            "function onDetection(fn) { this.__yolo_onDetection = fn; }\n" +
            "function getLastDetections() { try { return JSON.parse(robot.getLastDetections()); } catch(e) { return []; } }\n";
        
        cx.evaluateString(scope, helperFunctions, "helpers", 1, null);

        // remember global scope so Java can call detection callbacks
        synchronized (this) {
            this.globalScope = scope;
        }

        // Define a JS console object that delegates to robot.log()
        cx.evaluateString(scope,
                "var console = { log: function(msg) { robot.log(msg); } };",
                "console_helper", 1, null);
    }
    
    /**
     * Append a message to the output with timestamp.
     */
    private void appendOutput(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        output.append("[").append(timestamp).append("] ").append(message).append("\n");
        Log.d(TAG, message);
    }
    
    /**
     * Robot API class exposed to JavaScript.
     * All public methods are callable from JS.
     */
    public class RobotApi {
        private final RobotCommandCallback callback;
        
        public RobotApi(RobotCommandCallback callback) {
            this.callback = callback;
        }
        
        /**
         * Move the robot in a direction.
         * @param direction "forward", "backward", "left", "right"
         * @param speed 0.0 to 1.0
         */
        public void move(String direction, double speed) {
            checkRunning();
            float s = Math.max(0f, Math.min(1f, (float) speed));
            appendOutput("move('" + direction + "', " + s + ")");
            if (callback != null) {
                callback.onMove(direction, s);
            }
        }
        
        /**
         * Rotate the robot.
         * @param direction "left" or "right"
         * @param speed 0.0 to 1.0
         */
        public void rotate(String direction, double speed) {
            checkRunning();
            float s = Math.max(0f, Math.min(1f, (float) speed));
            appendOutput("rotate('" + direction + "', " + s + ")");
            if (callback != null) {
                callback.onRotate(direction, s);
            }
        }
        
        /**
         * Stop all motors.
         */
        public void stop() {
            appendOutput("stop()");
            if (callback != null) {
                callback.onStop();
            }
        }
        
        /**
         * Wait for a specified number of milliseconds.
         * @param ms milliseconds to wait
         */
        // Use 'sleep' to avoid colliding with Object.wait()
        public void sleep(int ms) throws InterruptedException {
            checkRunning();
            appendOutput("wait(" + ms + "ms)");

            // Break the wait into smaller chunks so we can check if stopped
            int remaining = ms;
            while (remaining > 0 && running.get()) {
                int sleepTime = Math.min(remaining, 100);
                Thread.sleep(sleepTime);
                remaining -= sleepTime;
            }

            if (!running.get()) {
                throw new InterruptedException("Script stopped");
            }
        }

        // Keep legacy 'wait' signature but delegate to sleep to avoid direct Object.wait use
        public void wait(int ms) throws InterruptedException {
            sleep(ms);
        }
        
        /**
         * Log a message to the output.
         * @param message the message to log
         */
        public void log(Object message) {
            appendOutput("LOG: " + (message != null ? message.toString() : "null"));
        }

        /**
         * Return the last detections JSON string (as stored by Java side).
         */
        public String getLastDetections() {
            return lastDetectionsJson != null ? lastDetectionsJson : "[]";
        }
        
        /**
         * Check if the script is still running, throw if not.
         */
        private void checkRunning() {
            if (!running.get()) {
                throw new RuntimeException("Script stopped");
            }
        }
    }
}
