package io.github.iwinties.herald;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the Herald service after boot. PLAN-20261125-herald-v1. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        c.startForegroundService(new Intent(c, HeraldService.class));
    }
}
