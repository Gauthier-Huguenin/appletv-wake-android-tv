package io.github.gauthierhuguenin.atvwake;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small rotating log in the app's external files dir, readable without root:
 * {@code adb shell cat /sdcard/Android/data/io.github.gauthierhuguenin.atvwake/files/log.txt}.
 */
final class Log {
    private static final long MAX_SIZE = 256 * 1024;

    private Log() {}

    static synchronized void write(Context context, String msg) {
        android.util.Log.i("AppleTVWake", msg);
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        File f = new File(dir, "log.txt");
        if (f.length() > MAX_SIZE) f.renameTo(new File(dir, "log.old.txt"));
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n";
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line);
        } catch (IOException ignored) {
        }
    }
}
