package com.bliss.headless;

/**
 * Binder protocol shared by the UI process and the Shizuku user service.
 *
 * <p>All commands are synchronous {@code IBinder.transact} calls. JSON commands
 * answer with a JSON string; frame commands answer with
 * {@code int ok, String error, byte[] jpeg}.
 */
final class Protocol {
    private Protocol() {
    }

    static final int VERSION = 2;

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
    static final int CMD_SHUTDOWN = 14;
    static final int CMD_LOG_TAIL = 16;

    // Repackaged clones
    static final int CMD_CLONE_LIST = 20;
    static final int CMD_CLONE_CREATE_INSTALLED = 21;
    static final int CMD_CLONE_CREATE_APK = 22;
    static final int CMD_CLONE_LAUNCH = 23;
    static final int CMD_CLONE_KILL = 24;
    static final int CMD_CLONE_DELETE = 25;
    static final int CMD_AUTH = 27;

    /** Class instantiated by Shizuku inside the shell process. Must stay public. */
    static final String SERVICE_CLASS = "com.bliss.headless.DisplayService";
    static final String SERVICE_TAG = "headless-display";
    static final int SERVICE_VERSION = 2;
}
