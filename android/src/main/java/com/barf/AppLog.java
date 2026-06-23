package com.barf;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-app logger that mirrors all messages to both Android logcat and an in-app
 * scrolling console. Call AppLog.i/d/w/e instead of Log.i/d/w/e anywhere you
 * want messages to appear on-screen.
 */
public class AppLog {
    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static volatile Listener listener;
    private static final SimpleDateFormat fmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public interface Listener {
        void onNewLine(String line);
    }

    public static void setListener(Listener l) {
        listener = l;
        // Replay existing lines to the new listener so it sees history
        if (l != null) {
            synchronized (AppLog.class) {
                for (String line : lines) l.onNewLine(line);
            }
        }
    }

    public static void i(String tag, String msg) { log("I", tag, msg); Log.i(tag, msg); }
    public static void d(String tag, String msg) { log("D", tag, msg); Log.d(tag, msg); }
    public static void w(String tag, String msg) { log("W", tag, msg); Log.w(tag, msg); }
    public static void e(String tag, String msg) { log("E", tag, msg); Log.e(tag, msg); }

    private static synchronized void log(String level, String tag, String msg) {
        String line = fmt.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        if (lines.size() >= MAX_LINES) lines.pollFirst();
        lines.addLast(line);
        Listener l = listener;
        if (l != null) l.onNewLine(line);
    }

    public static synchronized void clear() {
        lines.clear();
    }
}
