package com.shihab.diplay.siri;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Package launcher for HY's app-only key picker; never opens DiPlay's main screen. */
public final class SiriLauncherActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent siri = new Intent("com.shihab.diplay.action.SIRI")
            .setPackage("com.shihab.diplay")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (siri.resolveActivity(getPackageManager()) != null) {
            startActivity(siri);
        } else {
            Toast.makeText(this, R.string.diplay_update_required, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
