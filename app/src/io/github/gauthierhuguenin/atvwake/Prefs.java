package io.github.gauthierhuguenin.atvwake;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings written once by scripts/setup.sh through {@link ConfigReceiver}. */
final class Prefs {
    static final String CREDENTIALS = "credentials";
    static final String APPLE_TV_NAME = "apple_tv_name";
    static final String MAIN_INPUT = "main_input";
    static final String MAIN_LABEL = "main_label";
    static final String SECOND_INPUT = "second_input";
    static final String SECOND_LABEL = "second_label";
    static final String CURRENT_INPUT = "current_input";

    /** Stable id sent to the Apple TV; without it tvOS does not push power state events. */
    static final String RP_ID = "a1b2c3d4e5f6";
    /** Maximum delay between the two mute presses of a double press. */
    static final long DOUBLE_MUTE_MS = 700;

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    static String string(Context context, String key) {
        return get(context).getString(key, null);
    }

    static boolean hasAppleTv(Context context) {
        return string(context, CREDENTIALS) != null && string(context, APPLE_TV_NAME) != null;
    }

    static String label(Context context, String input) {
        if (input != null && input.equals(string(context, SECOND_INPUT))) {
            return get(context).getString(SECOND_LABEL, "Input 2");
        }
        return get(context).getString(MAIN_LABEL, "Apple TV");
    }
}
