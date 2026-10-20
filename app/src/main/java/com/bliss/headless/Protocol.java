package com.bliss.headless;

/**
 * Binder protocol shared by the UI process and the Shizuku user service.
 *
 * <p>All commands are synchronous {@code IBinder.transact} calls. JSON commands
 * answer with a JSON string; frame commands answer with
 * {@code int ok, int width, int height, long timestamp, byte[] jpeg}.
 */
final class Protocol {
    private Protocol() {
    }

    static final int VERSION = 1;

    static final int CMD_STATUS = 1;
    static final int CMD_CREATE = 2;
    static final int CMD_RELEASE = 3;
    static final int CMD_LIST_APPS = 4;
    static final int CMD_LAUNCH_APP = 5;
    static final int CMD_CAPTURE = 6;
    static final int CMD_TAP = 7;
    static final int CMD_SWIPE = 8;
    static final int CMD_KEY = 9;
    static final int CMD_TEXT = 10;
    static final int CMD_SELF_TEST_START = 11;
    static final int CMD_SELF_TEST_STATUS = 12;
    static final int CMD_EVIDENCE = 13;
    static final int CMD_SHUTDOWN = 14;
    static final int CMD_LAUNCH_TEST_ACTIVITY = 15;
    static final int CMD_LOG_TAIL = 16;

    /** Class instantiated by Shizuku inside the shell process. Must stay public. */
    static final String SERVICE_CLASS = "com.bliss.headless.DisplayService";
    static final String SERVICE_TAG = "headless-display";
    static final int SERVICE_VERSION = 1;

    static final String TEST_ACTIVITY = "com.bliss.headless/.TestPatternActivity";
}
