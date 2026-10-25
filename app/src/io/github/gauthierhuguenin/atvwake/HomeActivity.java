package io.github.gauthierhuguenin.atvwake;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Optional Home replacement: always goes straight to the main input (the Apple TV). */
public class HomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        go();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        go();
    }

    private void go() {
        // If Home comes back right after a switch, the switch failed: show the picker instead of looping.
        if (Inputs.tunedRecently() || !Inputs.tuneMain(this)) {
            startActivity(new Intent(this, PickerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}
