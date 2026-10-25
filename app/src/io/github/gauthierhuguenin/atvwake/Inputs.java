package io.github.gauthierhuguenin.atvwake;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.widget.Toast;

/** Switches HDMI inputs through the TV's own Live TV app (ACTION_VIEW on a passthrough channel URI). */
final class Inputs {
    private static long lastTune;

    private Inputs() {}

    static boolean tune(Context context, String inputId) {
        if (inputId == null) return false;
        Intent intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            lastTune = System.currentTimeMillis();
            Prefs.get(context).edit().putString(Prefs.CURRENT_INPUT, inputId).apply();
            Log.write(context, "input -> " + inputId);
            return true;
        } catch (RuntimeException e) {
            Log.write(context, "cannot switch input: " + e);
            return false;
        }
    }

    static boolean tuneMain(Context context) {
        return tune(context, Prefs.string(context, Prefs.MAIN_INPUT));
    }

    /** Goes to the other configured input, based on the last switch made by the app. */
    static void toggle(Context context) {
        String main = Prefs.string(context, Prefs.MAIN_INPUT);
        String second = Prefs.string(context, Prefs.SECOND_INPUT);
        if (main == null || second == null) {
            Log.write(context, "toggle ignored: inputs are not configured");
            return;
        }
        String target = second.equals(Prefs.string(context, Prefs.CURRENT_INPUT)) ? main : second;
        if (tune(context, target)) {
            Toast.makeText(context, Prefs.label(context, target), Toast.LENGTH_SHORT).show();
        }
    }

    /** True right after a switch: avoids a loop if Home comes back immediately. */
    static boolean tunedRecently() {
        return System.currentTimeMillis() - lastTune < 3000;
    }
}
