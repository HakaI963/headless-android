package com.bliss.headless;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Installed-app enumeration and the {@code am start --display <id>} launch path.
 *
 * <p>Adapted from ShadowAuto {@code android-shell/.../AppCatalog.java}
 * (Apache-2.0). The launch command is the exact mechanism that makes the
 * display useful: any launchable app can be placed on the virtual display
 * without touching the physical screen.
 */
final class AppCatalog {
    private AppCatalog() {
    }

    static final class AppInfo {
        final String label;
        final String packageName;

        AppInfo(String label, String packageName) {
            this.label = label == null || label.trim().isEmpty() ? packageName : label;
            this.packageName = packageName;
        }
    }

    static final class CommandResult {
        final int exit;
        final String output;

        CommandResult(int exit, String output) {
            this.exit = exit;
            this.output = output == null ? "" : output;
        }

        boolean ok() {
            return exit == 0;
        }
    }

    static List<AppInfo> installedApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppInfo> result = new ArrayList<>();
        List<ApplicationInfo> applications = pm.getInstalledApplications(0);
        for (ApplicationInfo application : applications) {
            if (pm.getLaunchIntentForPackage(application.packageName) == null) {
                continue;
            }
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(application));
            } catch (Throwable ignored) {
                label = application.packageName;
            }
            result.add(new AppInfo(label, application.packageName));
        }
        result.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.US)));
        return result;
    }

    static String appsJson(Context context) {
        JSONArray array = new JSONArray();
        for (AppInfo app : installedApps(context)) {
            try {
                JSONObject object = new JSONObject();
                object.put("label", app.label);
                object.put("package", app.packageName);
                array.put(object);
            } catch (Throwable ignored) {
            }
        }
        return array.toString();
    }

    static CommandResult launchPackage(Context context, String packageName, int displayId,
                                       boolean forceStop) throws Exception {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) {
            throw new IllegalStateException("no launch intent for " + packageName);
        }
        ComponentName component = intent.getComponent();
        if (component == null) {
            throw new IllegalStateException("no launch component for " + packageName);
        }
        return launchComponent(component, displayId, forceStop);
    }

    static CommandResult launchComponent(ComponentName component, int displayId, boolean forceStop)
            throws Exception {
        StringBuilder command = new StringBuilder();
        if (forceStop) {
            command.append("/system/bin/am force-stop ")
                    .append(quote(component.getPackageName()))
                    .append(" ; ");
        }
        command.append("/system/bin/am start --display ").append(displayId)
                .append(" -f 0x10000000")
                .append(" -n ").append(quote(component.flattenToShortString()));
        return run(command.toString());
    }

    static CommandResult run(String command) throws Exception {
        Process process = new ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(25, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, output.append("[timeout]").toString());
        }
        return new CommandResult(process.exitValue(), output.toString().trim());
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
