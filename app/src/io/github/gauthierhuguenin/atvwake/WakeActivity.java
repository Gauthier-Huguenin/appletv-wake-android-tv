package io.github.gauthierhuguenin.atvwake;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;

/**
 * Invisible activity that turns the screen on, then switches to the main input.
 * TCL firmware ignores ACQUIRE_CAUSES_WAKEUP wake locks, but honours setTurnScreenOn.
 */
public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            Log.write(this, "after wake: screen " + (pm.isInteractive() ? "on" : "still off"));
            Inputs.tuneMain(this);
            finish();
        }, 2_000);
    }
}
