package com.myklharu.stride;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires a reminder, then books the same time tomorrow. Also restores reminders after a restart or update. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Reminders.scheduleAll(context);
            return;
        }
        String id = intent.getStringExtra("id");
        if (id == null) return;
        Reminders.show(context, id);
        Reminders.schedule(context, id);
    }
}
