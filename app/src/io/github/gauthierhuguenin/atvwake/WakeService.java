package io.github.gauthierhuguenin.atvwake;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Always-on service. An accessibility service is used only because the system keeps it bound
 * (and restarts it at boot); it reads no screen content and filters no keys.
 *
 * <ul>
 *   <li>Watches the Apple TV and turns the TV on when the Apple TV wakes up.</li>
 *   <li>A double press on mute (sent by the Apple TV remote over HDMI-CEC) toggles between the two inputs.</li>
 * </ul>
 */
public class WakeService extends AccessibilityService {
    /** Broadcast by AudioService on every mute change (hidden AudioManager constant). */
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final int STREAM_MUSIC = 3;

    static volatile WakeService instance;

    private AppleTvWatcher watcher;
    private long lastMute;

    // CEC mute from the Apple TV remote never reaches onKeyEvent on TCL sets, so we follow its effect.
    private final BroadcastReceiver muteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != STREAM_MUSIC) return;
            long t = SystemClock.uptimeMillis();
            if (t - lastMute < Prefs.DOUBLE_MUTE_MS) {
                lastMute = 0;
                // Two presses: muted then unmuted, so the sound ends up unchanged.
                Log.write(WakeService.this, "double mute: toggling input");
                Inputs.toggle(WakeService.this);
            } else {
                lastMute = t;
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.write(this, "service started");
        registerReceiver(muteReceiver, new IntentFilter(MUTE_CHANGED));
        restartWatcher();
    }

    synchronized void restartWatcher() {
        if (watcher != null) watcher.stop();
        watcher = null;
        if (!Prefs.hasAppleTv(this)) {
            Log.write(this, "no Apple TV configured yet: run scripts/setup.sh");
            return;
        }
        watcher = new AppleTvWatcher(this);
        watcher.start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        try {
            unregisterReceiver(muteReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        synchronized (this) {
            if (watcher != null) watcher.stop();
        }
        super.onDestroy();
    }
}
