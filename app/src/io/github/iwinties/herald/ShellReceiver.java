package io.github.iwinties.herald;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The manifest restricts this headless setup action to DUMP callers. */
public final class ShellReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"io.github.iwinties.herald.action.ENABLE_RECEIVING".equals(intent.getAction())) return;
        boolean before = HeraldService.receiving(context);
        boolean accepted = HeraldService.requestStart(context, before);
        setResultCode(accepted ? android.app.Activity.RESULT_OK : android.app.Activity.RESULT_CANCELED);
    }
}
