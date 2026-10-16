package org.telegram.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.LinkedHashMap;

// Keeps Tegram's own AI work running after leaving the app (voice message summaries, shopping list scans): a
// foreground service alive while any task is registered, with one quiet notification showing each task's step and
// progress. Tasks are registered from a tap, so the app is in the foreground when the service starts. UI thread only.
public class MyWorkService extends Service {

    private static final String CHANNEL = "my_ai_work";
    private static final int NOTIFICATION_ID = 0x7E7F00;

    private static class Task {
        String title;
        String stage;
        int percent; // 0: indeterminate
    }

    private static final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();

    public static NotificationManager notificationManager() {
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "AI tasks in progress", NotificationManager.IMPORTANCE_LOW));
        }
        return manager;
    }

    // Adds or updates a task; the first one starts the service.
    public static void put(String key, String title, String stage, int percent) {
        Task task = tasks.get(key);
        boolean added = task == null;
        if (added) {
            task = new Task();
            tasks.put(key, task);
        }
        task.title = title;
        task.stage = stage;
        task.percent = percent;
        if (added) {
            update();
        } else {
            notificationManager().notify(NOTIFICATION_ID, build(ApplicationLoader.applicationContext));
        }
    }

    // Removes a finished task; the last one stops the service.
    public static void remove(String key) {
        if (tasks.remove(key) != null) {
            update();
        }
    }

    private static void update() {
        Context context = ApplicationLoader.applicationContext;
        Intent intent = new Intent(context, MyWorkService.class);
        try {
            if (tasks.isEmpty()) {
                context.stopService(intent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            FileLog.e(e); // still runs while the app stays open
        }
    }

    // One task: its title, step and progress bar. Several: a line each.
    private static Notification build(Context context) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(MyIcons.notificationIcon())
            .setOnlyAlertOnce(true)
            .setOngoing(true);
        if (tasks.size() == 1) {
            Task task = tasks.values().iterator().next();
            builder.setContentTitle(task.title)
                .setContentText(task.stage)
                .setProgress(100, task.percent, task.percent <= 0);
        } else {
            NotificationCompat.InboxStyle lines = new NotificationCompat.InboxStyle();
            for (Task task : tasks.values()) {
                lines.addLine(task.title + ": " + task.stage);
            }
            builder.setContentTitle(Math.max(1, tasks.size()) + " AI tasks running")
                .setStyle(lines)
                .setProgress(0, 0, true);
        }
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        notificationManager();
        Notification notification = build(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (tasks.isEmpty()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
