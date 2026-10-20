package com.bliss.headless;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Saves JPEG/text evidence to Downloads/HeadlessAndroid without permissions. */
final class Downloads {
    private Downloads() {
    }

    static String save(Context context, String displayName, String mimeType, byte[] data) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/HeadlessAndroid");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("openOutputStream returned null");
                }
                out.write(data);
            }
            return "Downloads/HeadlessAndroid/" + displayName;
        } catch (Throwable e) {
            try {
                File dir = new File(context.getExternalFilesDir(null), "evidence");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("cannot create " + dir);
                }
                File file = new File(dir, displayName);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                return file.getAbsolutePath();
            } catch (Throwable fallback) {
                return "save failed: " + fallback.getMessage();
            }
        }
    }
}
