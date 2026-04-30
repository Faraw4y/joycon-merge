package com.joyconmerge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            Intent svc = new Intent(context, MergeService.class);
            svc.setAction(MergeService.ACTION_START);
            context.startForegroundService(svc);
        }
    }
}
