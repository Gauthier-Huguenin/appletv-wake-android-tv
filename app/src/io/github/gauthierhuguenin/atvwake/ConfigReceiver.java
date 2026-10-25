package io.github.gauthierhuguenin.atvwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.List;

/**
 * Talks to scripts/setup.sh over adb:
 * <ul>
 *   <li>{@code am broadcast -n <pkg>/.ConfigReceiver --ez list_inputs true} returns the HDMI inputs.</li>
 *   <li>{@code am broadcast -n <pkg>/.ConfigReceiver --es main_input ... } stores the configuration.</li>
 * </ul>
 * Protected by the DUMP permission in the manifest, which adb's shell holds and regular apps do not.
 */
public class ConfigReceiver extends BroadcastReceiver {
    private static final String[] KEYS = {
        Prefs.CREDENTIALS, Prefs.APPLE_TV_NAME, Prefs.MAIN_INPUT, Prefs.MAIN_LABEL,
        Prefs.SECOND_INPUT, Prefs.SECOND_LABEL,
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getBooleanExtra("list_inputs", false)) {
            setResultData(listInputs(context));
            return;
        }
        SharedPreferences.Editor editor = Prefs.get(context).edit();
        StringBuilder changed = new StringBuilder();
        for (String key : KEYS) {
            String value = intent.getStringExtra(key);
            if (value == null) continue;
            if (value.isEmpty()) editor.remove(key); else editor.putString(key, value);
            changed.append(' ').append(key);
        }
        editor.commit();
        Log.write(context, "configuration updated:" + changed);
        setResultData("ok");
        WakeService service = WakeService.instance;
        if (service != null) service.restartWatcher();
    }

    /**
     * One line per physical HDMI port: {@code id|label|name of the CEC device plugged in}, joined with
     * ";". The id is the hardware input (stable even when the device is off).
     */
    private static String listInputs(Context context) {
        TvInputManager tv = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> all = tv.getTvInputList();
        StringBuilder out = new StringBuilder();
        for (TvInputInfo info : all) {
            if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
            String cec = "";
            for (TvInputInfo child : all) {
                // HDMI-CEC devices show up as child inputs labelled with the device's own name.
                if (info.getId().equals(child.getParentId())) cec = child.loadLabel(context).toString();
            }
            if (out.length() > 0) out.append(';');
            out.append(info.getId()).append('|').append(clean(info.loadLabel(context))).append('|').append(clean(cec));
        }
        return out.toString();
    }

    private static String clean(CharSequence s) {
        return s == null ? "" : s.toString().replace('|', ' ').replace(';', ' ').replace('"', ' ');
    }
}
