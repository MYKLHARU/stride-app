package com.myklharu.stride;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;

/** Daily reminders: stored in SharedPreferences, scheduled with AlarmManager, shown as notifications. */
public final class Reminders {
    static final String CHANNEL = "daily_reminders";
    private static final String PREFS = "reminders";

    private Reminders() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void set(Context c, String id, int hour, int minute, boolean on, String title, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("h", hour);
            o.put("m", minute);
            o.put("on", on);
            o.put("title", title);
            o.put("text", text);
            prefs(c).edit().putString(id, o.toString()).apply();
        } catch (Exception ignored) {
        }
        if (on) schedule(c, id);
        else cancel(c, id);
    }

    static JSONObject get(Context c, String id) {
        try {
            String s = prefs(c).getString(id, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static String all(Context c) {
        JSONObject out = new JSONObject();
        for (String id : prefs(c).getAll().keySet()) {
            try {
                JSONObject o = get(c, id);
                if (o != null) out.put(id, o);
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    private static PendingIntent alarmIntent(Context c, String id) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction("com.myklharu.stride.REMIND." + id);
        i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, String id) {
        JSONObject o = get(c, id);
        if (o == null || !o.optBoolean("on")) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, o.optInt("h"));
        cal.set(Calendar.MINUTE, o.optInt("m"));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), alarmIntent(c, id));
    }

    static void cancel(Context c, String id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(c, id));
    }

    static void scheduleAll(Context c) {
        for (String id : prefs(c).getAll().keySet()) schedule(c, id);
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Daily reminders", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Reminders to weigh in, log steps, rate your hunger and log food");
            nm.createNotificationChannel(ch);
        }
    }

    static void show(Context c, String id) {
        JSONObject o = get(c, id);
        if (o == null || !o.optBoolean("on")) return;
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class);
        open.putExtra("open", id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFFD9512B)
                .setContentTitle(o.optString("title", "Stride"))
                .setContentText(o.optString("text", ""))
                .setStyle(new Notification.BigTextStyle().bigText(o.optString("text", "")))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id.hashCode(), n);
    }
}
